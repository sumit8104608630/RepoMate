package devPilot.backend.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import reactor.core.publisher.Flux;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    private static final String DEFAULT_EMBEDDING_MODEL = "openai/text-embedding-3-small";
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1536;

    private static String mask(String key) {
        if (key == null || key.isEmpty()) return "<EMPTY>";
        if (key.length() <= 14) return key.substring(0, Math.min(4, key.length())) + "…";
        return key.substring(0, 10) + "…" + key.substring(key.length() - 4);
    }

    private static String resolveApiKey(String propertyKey) {
        String key = propertyKey == null ? "" : propertyKey.trim();
        if (!key.isEmpty() && !key.startsWith("${")) {
            log.info("OPENROUTER_API_KEY resolved via Spring property spring.ai.openai.api-key; prefix={}", mask(key));
            return key;
        }
        String env = System.getenv("OPENROUTER_API_KEY");
        if (env != null && !env.isBlank()) {
            log.info("OPENROUTER_API_KEY resolved via direct System.getenv(OPENROUTER_API_KEY); prefix={}", mask(env));
            return env.trim();
        }
        log.warn("OPENROUTER_API_KEY is EMPTY. Embeddings and chat will fail with 401 from OpenRouter. "
                + "Set env OPENROUTER_API_KEY or property spring.ai.openai.api-key on Render.");
        return "";
    }

    private static void validateKey(String key, String model) {
        if (key == null || key.isEmpty()) {
            log.error("OpenRouter key configuration is EMPTY. Embedding/chat calls will fail.");
            return;
        }
        if (!key.startsWith("sk-or-v1-")) {
            log.warn("OpenRouter key does not start with 'sk-or-v1-' (got prefix '{}'). "
                    + "OpenRouter standard keys use this prefix. Verify the Render env OPENROUTER_API_KEY value.",
                    key.length() >= 10 ? key.substring(0, 10) : key);
        } else {
            log.info("OpenRouter key format looks valid (sk-or-v1-…). Model for embeddings/chat: {}", model);
        }
        log.info("Quick curl smoke-test (run from Render shell / your terminal to rule out key-type issues):\n"
                + "  curl -sS -H \"Authorization: Bearer $OPENROUTER_API_KEY\" https://openrouter.ai/api/v1/models | head -c 400\n"
                + "  If this also returns 401 {{\"error\":{\"message\":\"User not found.\",\"code\":401}} then the key itself is bad:\n"
                + "    - Go to https://openrouter.ai/settings/keys and click \"Create Key\" under the standard API Keys section (NOT the Provisioning tab).\n"
                + "    - Provisioning/management keys return 401 User not found for ALL chat/embedding calls even though they share the sk-or-v1- format.\n"
                + "    - Expired standard keys also return this misleading 401 instead of 'Key expired'.");
    }

    @Bean(name = "openRouterRestClient")
    RestClient openRouterRestClient(
            @Value("${spring.ai.openai.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${app.openrouter.referer:https://repomate-bfie.onrender.com}") String referer,
            @Value("${app.openrouter.title:DevPilot}") String title) {
        String resolvedKey = resolveApiKey(apiKey);
        String trimmedBase = StringUtils.trimTrailingCharacter(baseUrl, '/');
        log.info("OpenRouter RestClient: baseUrl={}, referer={}, title={}, keyPrefix={}",
                trimmedBase, referer, title, mask(resolvedKey));
        validateKey(resolvedKey, DEFAULT_EMBEDDING_MODEL + " / openai/gpt-4o-mini");
        return RestClient.builder()
                .baseUrl(trimmedBase)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + resolvedKey)
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
        log.info("Registering @Primary OpenRouterEmbeddingModel: model={}, dimensions={}", model, dimensions);
        return new OpenRouterEmbeddingModel(openRouterRestClient, model, dimensions);
    }

    @Primary
    @Bean(name = "openRouterChatModel")
    ChatModel openRouterChatModel(
            RestClient openRouterRestClient,
            @Value("${spring.ai.openai.chat.options.model:openai/gpt-4o-mini}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:0.2}") double temperature,
            @Value("${spring.ai.openai.chat.options.max-tokens:4096}") int maxTokens) {
        log.info("Registering @Primary OpenRouterChatModel: model={}, temperature={}, maxTokens={}", model, temperature, maxTokens);
        return new OpenRouterChatModel(openRouterRestClient, model, temperature, maxTokens);
    }

    private static RuntimeException translateOpenRouter(HttpClientErrorException ex, String endpoint) {
        String body = ex.getResponseBodyAsString();
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == HttpStatus.UNAUTHORIZED && (body.contains("User not found") || body.contains("\"code\":401"))) {
            String msg = "OpenRouter returned 401 'User not found' on " + endpoint
                    + ". This is a KEY TYPE / KEY VALIDITY issue, not a code bug. Fix by:\n"
                    + "  (1) Verify Render env OPENROUTER_API_KEY uses a STANDARD key created at https://openrouter.ai/settings/keys\n"
                    + "      via the 'Create Key' button at the top of the page — NOT the Provisioning / management keys section.\n"
                    + "      Provisioning keys use the same sk-or-v1- format but always return 401 for chat/embedding calls.\n"
                    + "  (2) If the key is standard, it may be EXPIRED (OpenRouter returns 'User not found' instead of 'Key expired').\n"
                    + "      Create a new standard key at the dashboard and replace OPENROUTER_API_KEY in Render.\n"
                    + "  (3) Validate by running: curl -sS -H \"Authorization: Bearer $OPENROUTER_API_KEY\" https://openrouter.ai/api/v1/models\n"
                    + "      Expected: JSON model list. If you see 401 User not found, the key is invalid regardless of DevPilot.\n"
                    + "  Raw body: " + body;
            log.error(msg);
            return new RuntimeException(msg, ex);
        }
        return ex;
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

            Map<String, Object> payload;
            try {
                payload = client.post()
                        .uri("/embeddings")
                        .body(body)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            } catch (HttpClientErrorException ex) {
                throw translateOpenRouter(ex, "POST /embeddings (model=" + model + ")");
            }

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
        private final int maxTokens;

        OpenRouterChatModel(RestClient client, String model, double temperature, int maxTokens) {
            this.client = client;
            this.model = model;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
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
            int capped = maxTokens;
            if (options != null && options.getMaxTokens() != null) {
                capped = Math.min(options.getMaxTokens(), maxTokens);
            }
            body.put("max_tokens", capped);
            if (options != null && options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
                List<String> stops = options.getStopSequences();
                body.put("stop", stops.size() == 1 ? stops.get(0) : stops);
            }

            Map<String, Object> payload;
            try {
                payload = client.post()
                        .uri("/chat/completions")
                        .body(body)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            } catch (HttpClientErrorException ex) {
                throw translateOpenRouter(ex, "POST /chat/completions (model=" + model + ")");
            }

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
