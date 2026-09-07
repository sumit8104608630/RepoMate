package devPilot.backend.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import reactor.core.publisher.Flux;

@Configuration
public class AiConfig {

    private static final String DEFAULT_EMBEDDING_MODEL = "openai/text-embedding-3-small";
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1536;

    @Bean(name = "openRouterRestClient")
    RestClient openRouterRestClient(
            @Value("${spring.ai.openai.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${app.openrouter.referer:https://repomate-bfie.onrender.com}") String referer,
            @Value("${app.openrouter.title:DevPilot}") String title) {
        return RestClient.builder()
                .baseUrl(StringUtils.trimTrailingCharacter(baseUrl, '/'))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("HTTP-Referer", referer)
                .defaultHeader("X-Title", title)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    @Primary
    @Bean(name = "openRouterEmbeddingModel")
    EmbeddingModel openRouterEmbeddingModel(
            RestClient openRouterRestClient,
            @Value("${spring.ai.openai.embedding.options.model:" + DEFAULT_EMBEDDING_MODEL + "}") String model,
            @Value("${spring.ai.openai.embedding.options.dimensions:" + DEFAULT_EMBEDDING_DIMENSIONS + "}") int dimensions) {
        return new OpenRouterEmbeddingModel(openRouterRestClient, model, dimensions);
    }

    @Primary
    @Bean(name = "openRouterChatModel")
    ChatModel openRouterChatModel(
            RestClient openRouterRestClient,
            @Value("${spring.ai.openai.chat.options.model:openai/gpt-4o-mini}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:0.2}") double temperature) {
        return new OpenRouterChatModel(openRouterRestClient, model, temperature);
    }

    @SuppressWarnings("unchecked")
    private static final class OpenRouterEmbeddingModel implements EmbeddingModel {

        private final RestClient client;
        private final String model;
        private final int dimensions;

        OpenRouterEmbeddingModel(RestClient client, String model, int dimensions) {
            this.client = client;
            this.model = model;
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> inputs = new ArrayList<>(request.getInstructions());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", inputs.size() == 1 ? inputs.get(0) : inputs);
            body.put("dimensions", dimensions);

            Map<String, Object> payload = client.post()
                    .uri("/embeddings")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            List<Object> data = (List<Object>) payload.get("data");
            List<Embedding> embeddings = new ArrayList<>(data.size());
            for (Object item : data) {
                Map<String, Object> row = (Map<String, Object>) item;
                Number index = row.get("index") instanceof Number n ? n : 0;
                List<Object> rawVector = (List<Object>) row.get("embedding");
                float[] vector = new float[rawVector.size()];
                for (int i = 0; i < rawVector.size(); i++) {
                    vector[i] = ((Number) rawVector.get(i)).floatValue();
                }
                embeddings.add(new Embedding(vector, index.intValue()));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            String text = document == null ? "" : (document.getText() == null ? "" : document.getText());
            List<float[]> result = embed(List.of(text));
            return result.isEmpty() ? new float[0] : result.get(0);
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            if (texts == null || texts.isEmpty()) return List.of();
            EmbeddingResponse res = call(new EmbeddingRequest(texts, EmbeddingOptions.builder().build()));
            List<Embedding> embeddings = res.getResults();
            List<float[]> out = new ArrayList<>(embeddings.size());
            for (Embedding e : embeddings) {
                out.add(e.getOutput());
            }
            while (out.size() < texts.size()) {
                out.add(new float[0]);
            }
            return out;
        }

        public int dimensions() {
            return dimensions;
        }
    }

    @SuppressWarnings("unchecked")
    private static final class OpenRouterChatModel implements ChatModel {

        private final RestClient client;
        private final String model;
        private final double temperature;

        OpenRouterChatModel(RestClient client, String model, double temperature) {
            this.client = client;
            this.model = model;
            this.temperature = temperature;
        }

        private static String messageText(Message m) {
            if (m == null) return "";
            try {
                Object t = m.getClass().getMethod("getText").invoke(m);
                if (t != null) return t.toString();
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }
            try {
                Object t = m.getClass().getMethod("text").invoke(m);
                if (t != null) return t.toString();
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }
            try {
                java.lang.reflect.Field f = m.getClass().getDeclaredField("content");
                f.setAccessible(true);
                Object t = f.get(m);
                if (t != null) return t.toString();
            } catch (Exception ignored) {
            }
            try {
                java.lang.reflect.Field f = m.getClass().getSuperclass().getDeclaredField("content");
                f.setAccessible(true);
                Object t = f.get(m);
                if (t != null) return t.toString();
            } catch (Exception ignored) {
            }
            return "";
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Message m : prompt.getInstructions()) {
                Map<String, Object> msg = new LinkedHashMap<>();
                String role = switch (m) {
                    case SystemMessage sm -> "system";
                    case UserMessage um -> "user";
                    case AssistantMessage am -> "assistant";
                    case ToolResponseMessage trm -> "tool";
                    default -> {
                        if (m.getMessageType() != null) {
                            yield m.getMessageType().name().toLowerCase();
                        }
                        yield "user";
                    }
                };
                msg.put("role", role);
                msg.put("content", messageText(m));
                messages.add(msg);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", BigDecimal.valueOf(temperature));
            body.put("stream", false);
            ChatOptions options = prompt.getOptions();
            if (options != null && options.getMaxTokens() != null) {
                body.put("max_tokens", options.getMaxTokens());
            }
            if (options != null && options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
                List<String> stops = options.getStopSequences();
                body.put("stop", stops.size() == 1 ? stops.get(0) : stops);
            }

            Map<String, Object> payload = client.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            List<Object> choices = (List<Object>) payload.getOrDefault("choices", List.of());
            List<Generation> generations = new ArrayList<>(choices.size());
            for (Object item : choices) {
                Map<String, Object> first = (Map<String, Object>) item;
                Map<String, Object> msg = (Map<String, Object>) first.get("message");
                String text = msg == null ? "" : String.valueOf(msg.getOrDefault("content", ""));
                AssistantMessage assistant = new AssistantMessage(text);
                generations.add(new Generation(assistant));
            }
            return new ChatResponse(generations);
        }

        @Override
        @SuppressWarnings("deprecation")
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
