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

    /**
     * Stream a real LLM reply WITH progressive prompt shrinking for credit-limited users.
     *
     * <p>This is the PREFERRED entry point from {@link devPilot.backend.services.ChatService}.
     * It performs RAG retrieval + prompt building + LLM call all inside the same async work
     * task, so any {@link devPilot.backend.config.PromptTokenLimitException} raised by the
     * model can be caught IN SCOPE and immediately retried with a smaller prompt budget.
     * Trying the budget loop outside the async task (in ChatService) would miss the exception
     * because the model call happens inside a pool thread.</p>
     *
     * <p>Budget ladder (progressively smaller INPUT tokens):
     * 1. Full prompt:  TOP_K_CHUNKS chunks × MAX_CHARS_PER_CHUNK per chunk
     * 2. 3 chunks × 1000 chars
     * 3. 2 chunks × 600 chars
     * 4. 2 chunks × 300 chars
     * 5. 1 chunk  × 250 chars
     * 6. 1 chunk  × 120 chars   (tiny, just ~30 lines of code)
     * 7. 0 chunks × 0 chars      (zero-RAG fallback: pure LLM knowledge answer)
     *
     * <p>For level-7 we send a disclaimer prepended to the system prompt so the LLM knows
     * it's answering without repository context. If ALL 7 levels fail, we send a friendly
     * in-chat error directing the user to add credits.</p>
     */
    public SseEmitter streamWithBudgetRetry(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            CodeContextRetriever codeContextRetriever,
            ChatPromptBuilder chatPromptBuilder,
            UUID repositoryId,
            String repoFullName,
            String userContent) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        emitter.onTimeout(() -> {
            log.warn("ChatStreamHandler.streamWithBudgetRetry: SSE emitter timeout session={}", sessionId);
            sendErrorThenComplete(emitter, new RuntimeException("Response timed out"));
        });
        emitter.onError(t -> log.warn("ChatStreamHandler.streamWithBudgetRetry: emitter error session={}: {}", sessionId, t.toString()));
        emitter.onCompletion(() -> log.debug("ChatStreamHandler.streamWithBudgetRetry: emitter completed session={}", sessionId));

        record PromptBudget(int topK, int maxCharsPerChunk) {}
        List<PromptBudget> budgets = List.of(
                new PromptBudget(RagSettings.TOP_K_CHUNKS, RagSettings.MAX_CHARS_PER_CHUNK),
                new PromptBudget(3, 1000),
                new PromptBudget(2, 600),
                new PromptBudget(2, 300),
                new PromptBudget(1, 250),
                new PromptBudget(1, 120),
                new PromptBudget(0, 0)
        );

        Runnable work = () -> {
            StringBuilder fullReply = new StringBuilder();
            try {
                emitter.send(SseEmitter.event().name("user_message").data(savedUserMessage));

                // Budget loop — try smaller and smaller INPUTs if we keep getting
                // PromptTokenLimitException (402 INPUT tokens too many).
                devPilot.backend.config.PromptTokenLimitException lastPromptEx = null;
                List<CitationDto> finalCitations = List.of();
                String rawFinal = "";

                for (int bi = 0; bi < budgets.size(); bi++) {
                    PromptBudget b = budgets.get(bi);
                    String systemPrompt;
                    String userPrompt;
                    List<CitationDto> citations;
                    RetrievedContext retrievedContext;

                    if (b.topK() <= 0) {
                        // Zero-RAG fallback: LLM knowledge only.
                        retrievedContext = new RetrievedContext(List.of(), "");
                        citations = List.of();
                        systemPrompt = chatPromptBuilder.systemPrompt(repoFullName)
                                + "\n\nIMPORTANT NOTE: NO repository code context is available for this question."
                                + " If the user asks about specific code/structure, tell them you cannot see any files"
                                + " right now due to a temporary credit-budget limitation.";
                        userPrompt = chatPromptBuilder.userPrompt("", userContent);
                    } else {
                        retrievedContext = codeContextRetriever.retrieve(
                                repositoryId, userContent, b.topK(), b.maxCharsPerChunk());
                        citations = retrievedContext.citations();
                        systemPrompt = chatPromptBuilder.systemPrompt(repoFullName);
                        userPrompt = chatPromptBuilder.userPrompt(
                                retrievedContext.contextText() == null ? "" : retrievedContext.contextText(),
                                userContent);
                    }
                    finalCitations = citations;

                    // Build Spring AI prompt with current budget.
                    List<Message> messages = List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt));
                    Prompt prompt = new Prompt(messages);

                    try {
                        log.info("ChatStreamHandler.streamWithBudgetRetry: budgetAttempt={}/{} topK={} charsPerChunk={} session={}",
                                bi + 1, budgets.size(), b.topK(), b.maxCharsPerChunk(), sessionId);
                        ChatResponse response = chatModel.call(prompt);

                        String raw = "";
                        if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
                            var outputMessage = response.getResults().get(0).getOutput();
                            raw = extractMessageText(outputMessage);
                            log.info("ChatStreamHandler.streamWithBudgetRetry: SUCCESS at budgetAttempt={}/{} resultCount={} textLen={} session={}",
                                    bi + 1, budgets.size(), response.getResults().size(),
                                    raw == null ? 0 : raw.length(), sessionId);
                        } else {
                            log.warn("ChatStreamHandler.streamWithBudgetRetry: chatModel.call returned empty/null at budgetAttempt={}/{} session={}",
                                    bi + 1, budgets.size(), sessionId);
                        }
                        if (raw == null || raw.isBlank()) {
                            raw = "(no response from the model)";
                        }
                        rawFinal = raw;
                        lastPromptEx = null;
                        break; // SUCCESS — break out of budget loop
                    } catch (RuntimeException rtEx) {
                        // Drill to PromptTokenLimitException if it's the cause.
                        Throwable cause = rtEx;
                        devPilot.backend.config.PromptTokenLimitException pp = null;
                        while (cause != null) {
                            if (cause instanceof devPilot.backend.config.PromptTokenLimitException found) {
                                pp = found;
                                break;
                            }
                            cause = cause.getCause();
                        }
                        if (pp == null) {
                            // Not a prompt-input 402 — propagate (non-retryable here).
                            throw rtEx;
                        }
                        lastPromptEx = pp;
                        log.warn("ChatStreamHandler.streamWithBudgetRetry: PromptTokenLimit at budgetAttempt={}/{} topK={} charsPerChunk={}. "
                                + "Trying smaller prompt budget (if any left).",
                                bi + 1, budgets.size(), b.topK(), b.maxCharsPerChunk());
                        if (bi == budgets.size() - 1) {
                            // Last budget failed → break and deliver user-facing error.
                            break;
                        }
                    }
                }

                if (lastPromptEx != null) {
                    // All budget levels 402'd on INPUT tokens. Deliver friendly chat reply.
                    String raw = lastPromptEx.getMessage() == null ? "" : lastPromptEx.getMessage();
                    int nl = raw.indexOf('\n');
                    String firstLine = nl > 0 ? raw.substring(0, nl) : raw;
                    String safeMsg = "Sorry — I couldn't fit this question into your OpenRouter credit budget "
                            + "even with minimal context. The quickest fix is adding credits at "
                            + "https://openrouter.ai/settings/credits. (Raw error: " + firstLine + ")";
                    emitCharactersGradually(emitter, fullReply, safeMsg);
                    completeStream(emitter, sessionId, fullReply, List.of());
                    return;
                }

                emitCharactersGradually(emitter, fullReply, rawFinal);
                log.info("ChatStreamHandler.streamWithBudgetRetry: finishing replyLen={} session={}",
                        fullReply.length(), sessionId);
                completeStream(emitter, sessionId, fullReply, finalCitations);
            } catch (Exception ex) {
                log.error("ChatStreamHandler.streamWithBudgetRetry: error during work, session={}", sessionId, ex);
                sendErrorThenComplete(emitter, ex);
            }
        };
        submitOrRunDirect(work, "streamWithBudgetRetry");
        return emitter;
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
     * Emits tokens in batches with a fixed total animation budget.
     *
     * <p>Since our model does not support true token streaming (we receive the FULL answer
     * in one HTTP call), artificially sleeping one char at a time creates massive delays:
     * 2000 chars * 5ms = 10s of pure Thread.sleep() with zero network activity. The user
     * sees tokens arriving in DevTools but the UI bubble lags because we drip-feed them.</p>
     *
     * <p>This method instead:
     * <ul>
     *   <li>Divides the reply into a fixed number of chunks (MAX_FRAMES = ~20)</li>
     *   <li>Sends one chunk per frame at ~30fps (33ms per frame)</li>
     *   <li>Total animation is ALWAYS ~700ms max, regardless of reply length</li>
     *   <li>Short replies (< 80 chars) emit instantly for responsiveness</li>
     * </ul>
     * The UI still shows a smooth "growing text" effect but without frustrating waits.</p>
     */
    private void emitCharactersGradually(
            SseEmitter emitter, StringBuilder fullReply, String text) {
        if (text == null || text.isEmpty()) return;

        // Short replies (greetings, out-of-domain, errors): emit instantly
        if (text.length() <= 80) {
            appendToken(emitter, fullReply, text);
            return;
        }

        // Target ~30fps with ~20 frames → ~660ms total animation, independent of length
        final int maxFrames = 20;
        final long perFrameMillis = 33L;
        final int totalChars = text.length();
        final int frames = Math.min(maxFrames, totalChars);
        final int charsPerFrame = Math.max(1, totalChars / frames);

        int emitted = 0;
        boolean interrupted = false;
        for (int f = 0; f < frames; f++) {
            if (interrupted) break;
            int take;
            if (f == frames - 1) {
                take = totalChars - emitted; // last frame: any remainder
            } else {
                take = charsPerFrame;
            }
            if (take <= 0) break;
            int end = Math.min(emitted + take, totalChars);
            String chunk = text.substring(emitted, end);
            emitted = end;
            appendToken(emitter, fullReply, chunk);
            try {
                Thread.sleep(perFrameMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
            }
        }
        // Send any remaining characters (interrupt / rounding)
        if (emitted < totalChars) {
            appendToken(emitter, fullReply, text.substring(emitted));
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
                // Use the same smooth/fast batched emission as real LLM replies.
                emitCharactersGradually(emitter, fullReply, reply);
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
