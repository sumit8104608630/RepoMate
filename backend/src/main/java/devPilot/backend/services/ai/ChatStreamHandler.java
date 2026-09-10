package devPilot.backend.services.ai;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
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
        StringBuilder fullReply = new StringBuilder();

        Runnable work = () -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("user_message")
                        .data(savedUserMessage));

                ChatClient.builder(chatModel)
                        .build()
                        .prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .stream()
                        .content()
                        .doOnNext(token -> appendToken(emitter, fullReply, token))
                        .doOnError(err -> {
                            log.error("Chat stream error", err);
                            sendErrorThenComplete(emitter, err);
                        })
                        .doOnComplete(() -> completeStream(
                                emitter, sessionId, fullReply, citations))
                        .subscribe();
            } catch (Exception ex) {
                sendErrorThenComplete(emitter, ex);
            }
        };
        submitOrRunDirect(work, "stream(LLM)");
        return emitter;
    }

    /** Stream a canned / deterministic reply (greeting, out-of-domain) with no LLM call. */
    public SseEmitter sendFixedReply(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String reply) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);

        Runnable work = () -> {
            StringBuilder fullReply = new StringBuilder();
            try {
                emitter.send(SseEmitter.event().name("user_message").data(savedUserMessage));
                // Character-by-character token emission so the UI animates identically to
                // real LLM streams. Clients expect incremental "token" events.
                for (int i = 0; i < reply.length(); i++) {
                    appendToken(emitter, fullReply, String.valueOf(reply.charAt(i)));
                }
                completeStream(emitter, sessionId, fullReply, citations);
            } catch (Exception ex) {
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
