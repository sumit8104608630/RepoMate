package devPilot.backend.services.ai;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.CitationDto;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generation step: call OpenAI via Spring AI and stream tokens to the browser over SSE.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamHandler {

    private final ChatModel chatModel;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    @Qualifier("streamExecutor")
    private final Executor streamExecutor;

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        StringBuilder fullReply = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);

        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // emitter already closed
                }
            }
        };
        java.util.function.Consumer<Throwable> failWith = (err) -> {
            if (finished.compareAndSet(false, true)) {
                String message = extractUserMessage(err);
                try {
                    safeSend(emitter, SseEmitter.event()
                            .name("error")
                            .data(java.util.Collections.singletonMap("message", message),
                                    org.springframework.http.MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {
                    // emit error best-effort; if socket is gone there is nothing we can do
                }
                try {
                    emitter.completeWithError(err instanceof Exception ex ? ex : new RuntimeException(err));
                } catch (Exception ignored) {
                    // emitter already closed
                }
            }
        };

        emitter.onCompletion(() -> finished.set(true));
        emitter.onTimeout(() -> failWith.accept(new java.util.concurrent.TimeoutException("Stream timed out")));
        emitter.onError((ex) -> finished.set(true));

        try {
            safeSend(emitter, SseEmitter.event()
                    .name("user_message")
                    .data(savedUserMessage));
        } catch (Exception ex) {
            log.warn("Could not send user_message SSE event, session={}", sessionId, ex);
            failWith.accept(ex);
            return emitter;
        }

        streamExecutor.execute(() -> {
            try {
                ChatClient.builder(chatModel)
                        .build()
                        .prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .stream()
                        .content()
                        .doOnNext(token -> appendToken(emitter, fullReply, token, failWith))
                        .doOnError(err -> {
                            log.error("Chat stream error, session={}", sessionId, err);
                            failWith.accept(err);
                        })
                        .doOnComplete(() -> completeStream(
                                emitter, sessionId, fullReply, citations, finish, failWith))
                        .subscribe(
                                t -> {},
                                err -> { /* handled in doOnError */ },
                                () -> { /* handled in doOnComplete */ }
                        );
            } catch (Exception ex) {
                log.error("Failed to start chat stream, session={}", sessionId, ex);
                failWith.accept(ex);
            }
        });

        return emitter;
    }

    private void appendToken(
            SseEmitter emitter,
            StringBuilder fullReply,
            String token,
            java.util.function.Consumer<Throwable> failWith) {
        if (token == null || token.isEmpty()) return;
        fullReply.append(token);
        try {
            safeSend(emitter, SseEmitter.event()
                    .name("token")
                    .data(token, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            log.debug("Token send failed (client likely disconnected): {}", ex.toString());
            failWith.accept(ex);
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations,
            Runnable finish,
            java.util.function.Consumer<Throwable> failWith) {
        try {
            String content = fullReply.toString();
            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(content)
                    .citations(citationMapper.toJson(citations))
                    .build());

            safeSend(emitter, SseEmitter.event()
                    .name("assistant_message")
                    .data(toMessageResponse(assistant)));
            safeSend(emitter, SseEmitter.event().name("done").data("[DONE]"));
            finish.run();
        } catch (Exception ex) {
            log.error("Chat stream completion failed, session={}", sessionId, ex);
            failWith.accept(ex);
        }
    }

    private static void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder event) throws IOException {
        try {
            emitter.send(event);
        } catch (IllegalStateException ex) {
            // SseEmitter wraps IOException in IllegalStateException on timeout/disconnect.
            // Unwrap if possible so callers can treat it uniformly.
            if (ex.getCause() instanceof IOException ioCause) throw ioCause;
            throw ex;
        }
    }

    private static String extractUserMessage(Throwable t) {
        if (t == null) return "Unknown error";
        String msg = t.getMessage();
        // Unwrap common wrapper layers
        Throwable cause = t.getCause();
        if ((msg == null || msg.isBlank() || msg.equalsIgnoreCase("null")) && cause != null) {
            msg = cause.getMessage();
            t = cause;
            cause = t.getCause();
        }
        // Map common AI-provider HTTP codes to user-friendly text
        String lc = msg == null ? "" : msg.toLowerCase();
        if (msg == null || msg.isBlank()) return "Model request failed without a message";
        if (lc.contains("429") || lc.contains("too many requests") || lc.contains("rate limit")) {
            return "Rate limited by AI provider (HTTP 429). Please wait a moment or check your API key's usage quota.";
        }
        if (lc.contains("401") || lc.contains("unauthorized") || lc.contains("invalid api key")) {
            return "AI API key is invalid or expired (HTTP 401). Update the API key in application.properties.";
        }
        if (lc.contains("403") || lc.contains("forbidden")) {
            return "AI API request forbidden (HTTP 403). Check key permissions / billing.";
        }
        if (lc.contains("404") || lc.contains("not found") || lc.contains("model_not_found")) {
            return "AI model not found (HTTP 404). Make sure the requested model is enabled on your API key.";
        }
        if (lc.contains("insufficient_quota") || lc.contains("out of credit") || lc.contains("exceeded your current quota")) {
            return "AI API quota exceeded. Add billing credits or use a different API key.";
        }
        if (lc.contains("context_length_exceeded") || lc.contains("maximum context length")) {
            return "The prompt is too long for the selected model. Reduce the context size or re-index the repository.";
        }
        // Trim and limit verbosity
        String clean = msg.trim();
        if (clean.length() > 280) clean = clean.substring(0, 280) + "...";
        return clean;
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}