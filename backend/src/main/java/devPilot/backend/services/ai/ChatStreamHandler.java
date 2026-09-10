package devPilot.backend.services.ai;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.CitationDto;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.repository.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Generation step: call OpenAI via Spring AI and stream tokens to the browser over SSE.
 *
 * <p>All SSE emission runs on the dedicated {@code streamExecutor} thread pool. This is
 * critical because Spring MVC wires the {@code SseEmitter} to the HTTP response AFTER the
 * controller method returns. If we emitted tokens on the caller (HTTP) thread, the first
 * {@code emitter.send()} would throw BEFORE the response was even attached — and the
 * client would see a hanging request / empty reply. By offloading to the pool, the
 * controller returns the emitter immediately, Spring wires it, and a pool thread starts
 * emitting moments later.</p>
 *
 * <p>If the executor rejects the task (queue full / shutdown) we fall back to running
 * synchronously on the caller thread — acceptable for short canned replies.</p>
 */
@Component
@Slf4j
public class ChatStreamHandler {

    private final ChatModel chatModel;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    private final Executor streamExecutor;

    public ChatStreamHandler(
            ChatModel chatModel,
            ChatMessageRepository chatMessageRepository,
            CitationMapper citationMapper,
            @Qualifier("streamExecutor") Executor streamExecutor) {
        this.chatModel = chatModel;
        this.chatMessageRepository = chatMessageRepository;
        this.citationMapper = citationMapper;
        this.streamExecutor = streamExecutor;
    }

    /** Stream a real LLM reply. */
    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        emitter.onTimeout(() -> {
            log.warn("ChatStreamHandler.stream: SSE emitter timeout for session={}", sessionId);
            sendErrorThenComplete(emitter, new RuntimeException("Response timed out"));
        });
        emitter.onError(t -> log.warn("ChatStreamHandler.stream: SSE emitter error for session={}: {}", sessionId, t.toString()));
        emitter.onCompletion(() -> log.debug("ChatStreamHandler.stream: SSE emitter completed for session={}", sessionId));

        Runnable work = () -> {
            StringBuilder fullReply = new StringBuilder();
            try {
                log.info("ChatStreamHandler.stream: sending user_message event, session={}", sessionId);
                emitter.send(SseEmitter.event()
                        .name("user_message")
                        .data(savedUserMessage));

                List<Message> messages = List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userPrompt));
                Prompt prompt = new Prompt(messages);

                log.info("ChatStreamHandler.stream: calling chatModel.call(), session={}, systemPromptLen={}, userPromptLen={}",
                        sessionId, systemPrompt.length(), userPrompt.length());
                ChatResponse response = chatModel.call(prompt);

                String raw = "";
                if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
                    var outputMessage = response.getResults().get(0).getOutput();
                    raw = extractMessageText(outputMessage);
                    log.info("ChatStreamHandler.stream: received response, resultCount={}, firstResultTextLen={}, session={}",
                            response.getResults().size(), raw == null ? 0 : raw.length(), sessionId);
                } else {
                    log.warn("ChatStreamHandler.stream: chatModel.call returned empty/null results, session={}", sessionId);
                }

                if (raw == null || raw.isBlank()) {
                    raw = "(no response from the model)";
                }

                emitCharactersGradually(emitter, fullReply, raw);
                log.info("ChatStreamHandler.stream: completing stream, replyLen={}, session={}", fullReply.length(), sessionId);
                completeStream(emitter, sessionId, fullReply, citations);
            } catch (Exception ex) {
                log.error("ChatStreamHandler.stream: error during stream work, session={}", sessionId, ex);
                sendErrorThenComplete(emitter, ex);
            }
        };
        submitOrRunDirect(work, "stream(LLM)");
        return emitter;
    }

    /**
     * Safely extracts text from a Spring AI Message, trying multiple accessors
     * because the API has been inconsistent across versions (getContent vs getText).
     */
    static String extractMessageText(org.springframework.ai.chat.messages.Message m) {
        if (m == null) return "";
        try {
            java.lang.reflect.Method mGet = m.getClass().getMethod("getContent");
            Object v = mGet.invoke(m);
            if (v != null && !v.toString().isBlank()) return v.toString();
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Method mGet = m.getClass().getMethod("getText");
            Object v = mGet.invoke(m);
            if (v != null && !v.toString().isBlank()) return v.toString();
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Field f = m.getClass().getDeclaredField("content");
            f.setAccessible(true);
            Object v = f.get(m);
            if (v != null && !v.toString().isBlank()) return v.toString();
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Field f = m.getClass().getSuperclass().getDeclaredField("content");
            f.setAccessible(true);
            Object v = f.get(m);
            if (v != null && !v.toString().isBlank()) return v.toString();
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Emits characters one by one with a tiny per-char sleep, giving the UI a realistic
     * "typing" animation. This is used when the underlying model does not support true
     * token streaming (e.g. OpenRouter via our RestClient wrapper), so instead of dumping
     * the entire reply in a single "token" event and jarring the user, we reveal it
     * gradually.
     */
    private void emitCharactersGradually(
            SseEmitter emitter, StringBuilder fullReply, String text) {
        if (text == null || text.isEmpty()) return;
        long perCharNanos = text.length() <= 100 ? 12_000_000L
                : text.length() <= 500 ? 8_000_000L
                : 5_000_000L;
        for (int i = 0; i < text.length(); i++) {
            appendToken(emitter, fullReply, String.valueOf(text.charAt(i)));
            if (perCharNanos > 0) {
                try {
                    long millis = perCharNanos / 1_000_000L;
                    int nanos = (int) (perCharNanos % 1_000_000L);
                    if (millis > 0 || nanos > 0) {
                        Thread.sleep(millis, nanos);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    for (int j = i + 1; j < text.length(); j++) {
                        appendToken(emitter, fullReply, String.valueOf(text.charAt(j)));
                    }
                    break;
                }
            }
        }
    }

    /** Stream a canned / deterministic reply (greeting, out-of-domain) with no LLM call. */
    public SseEmitter sendFixedReply(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String reply) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        emitter.onTimeout(() -> {
            log.warn("ChatStreamHandler.sendFixedReply: SSE emitter timeout for session={}", sessionId);
            sendErrorThenComplete(emitter, new RuntimeException("Response timed out"));
        });
        emitter.onError(t -> log.warn("ChatStreamHandler.sendFixedReply: SSE emitter error for session={}: {}", sessionId, t.toString()));
        emitter.onCompletion(() -> log.debug("ChatStreamHandler.sendFixedReply: SSE emitter completed for session={}", sessionId));

        Runnable work = () -> {
            StringBuilder fullReply = new StringBuilder();
            try {
                log.info("ChatStreamHandler.sendFixedReply: session={}, replyLen={}", sessionId, reply.length());
                emitter.send(SseEmitter.event().name("user_message").data(savedUserMessage));
                // Character-by-character token emission so the UI animates identically to
                // real LLM streams. Clients expect incremental "token" events.
                for (int i = 0; i < reply.length(); i++) {
                    appendToken(emitter, fullReply, String.valueOf(reply.charAt(i)));
                }
                completeStream(emitter, sessionId, fullReply, citations);
            } catch (Exception ex) {
                log.error("ChatStreamHandler.sendFixedReply: error during work, session={}", sessionId, ex);
                sendErrorThenComplete(emitter, ex);
            }
        };
        submitOrRunDirect(work, "sendFixedReply(\"" + reply.substring(0, Math.min(20, reply.length())) + "\")");
        return emitter;
    }

    // --------------------------------------------------------------- internals

    private void submitOrRunDirect(Runnable work, String taskName) {
        try {
            streamExecutor.execute(work);
        } catch (RejectedExecutionException | NullPointerException ex) {
            log.warn("ChatStreamHandler: executor rejected task [{}]; running on caller thread. Cause: {}",
                    taskName, ex.toString());
            try {
                work.run();
            } catch (Exception runEx) {
                log.error("ChatStreamHandler: direct fallback also failed for [{}]", taskName, runEx);
            }
        }
    }

    private void appendToken(SseEmitter emitter, StringBuilder fullReply, String token) {
        fullReply.append(token);
        try {
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(token, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations) {
        try {
            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(fullReply.toString())
                    .citations(citationMapper.toJson(citations))
                    .build());

            emitter.send(SseEmitter.event()
                    .name("assistant_message")
                    .data(toMessageResponse(assistant)));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception ex) {
            sendErrorThenComplete(emitter, ex);
        }
    }

    private void sendErrorThenComplete(SseEmitter emitter, Throwable err) {
        try {
            String msg = err == null ? "stream error" : err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
            emitter.send(SseEmitter.event().name("error").data(msg, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {}
        try { emitter.completeWithError(err == null ? new RuntimeException("stream error") : err); }
        catch (Exception ignored) {}
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
