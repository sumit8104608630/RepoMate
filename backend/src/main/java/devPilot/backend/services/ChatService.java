package devPilot.backend.services;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.ChatSessionResponse;
import devPilot.backend.dto.CreateChatSessionRequest;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.ChatSession;
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.ChatMessageRepository;
import devPilot.backend.repository.ChatSessionRepository;
import devPilot.backend.services.ai.ChatPromptBuilder;
import devPilot.backend.services.ai.ChatStreamHandler;
import devPilot.backend.services.ai.CitationMapper;
import devPilot.backend.services.ai.CodeContextRetriever;
import devPilot.backend.services.ai.RagSettings;
import devPilot.backend.services.ai.RetrievedContext;
import lombok.RequiredArgsConstructor;

/**
 * Chat sessions and the RAG chat pipeline entry point.
 *
 * <p>{@link #streamReply} orchestrates the full flow:
 * validate → save user message → retrieve code context → build prompts → stream AI reply.
 * Each step is implemented in a dedicated class under {@code service.ai}.
 *
 * <p>Short-circuit paths:
 * <ul>
 *   <li>{@link #isGreeting(String)} — pure greetings get a canned reply, no LLM call.</li>
 *   <li>{@link RetrievedContext#contextText()} empty / NO_MATCHES → canned out-of-domain reply,
 *       no LLM call.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    static final String GREETING_REPLY = "hii iam repomate how ca i help you";
    static final String OUT_OF_DOMAIN_REPLY = "it is out of domain";
    private static final String NO_MATCHES = "(no matching code chunks found)";

    private static final Set<String> GREETING_TOKENS = Set.of(
            "hi", "hii", "hiii", "hello", "hallo", "helo", "hey", "heyy",
            "yo", "sup", "wassup", "whatsapp", "howdy", "hola", "namaste");

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RepoService repoService;
    private final CodeContextRetriever codeContextRetriever;
    private final ChatPromptBuilder chatPromptBuilder;
    private final ChatStreamHandler chatStreamHandler;
    private final CitationMapper citationMapper;

    @Transactional
    public ChatSessionResponse createSession(UUID userId, CreateChatSessionRequest request) {
        Repository repo = repoService.requireOwned(request.repositoryId(), userId);
        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository must be indexed before chatting");
        }

        String title = request.title() != null && !request.title().isBlank()
                ? request.title()
                : "Chat with " + repo.getFullName();

        ChatSession session = ChatSession.builder()
                .userId(userId)
                .repositoryId(repo.getId())
                .title(title)
                .build();
        session = chatSessionRepository.save(session);
        return toSessionResponse(session);
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> listSessions(UUID userId, UUID repositoryId) {
        repoService.requireOwned(repositoryId, userId);
        return chatSessionRepository
                .findByUserIdAndRepositoryIdOrderByCreatedAtDesc(userId, repositoryId)
                .stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(UUID userId, UUID sessionId) {
        ChatSession session = requireSession(userId, sessionId);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatSession requireSession(UUID userId, UUID sessionId) {
        return chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new NotFoundException("Chat session not found"));
    }

    public SseEmitter streamReply(UUID userId, UUID sessionId, String userContent) {
        // 1. Ensure the session exists and the repo is indexed
        ChatSession session = requireSession(userId, sessionId);
        Repository repo = repoService.requireOwned(session.getRepositoryId(), userId);
        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository is not ready for chat");
        }

        // 2. Persist the user's message
        ChatMessage userMessage = chatMessageRepository.save(ChatMessage.builder()
                .sessionId(session.getId())
                .role(MessageRole.USER)
                .content(userContent)
                .build());
        ChatMessageResponse savedUserResponse = toMessageResponse(userMessage);

        // 3. Greeting short-circuit — no LLM call, no RAG lookup
        if (isGreeting(userContent)) {
            return chatStreamHandler.sendFixedReply(
                    session.getId(), savedUserResponse, List.of(), GREETING_REPLY);
        }

        try {
            // 4. Do a quick retrieval on the caller (HTTP) thread to detect out-of-domain
            //    BEFORE offloading to the stream executor. This avoids wasting a pool thread
            //    on questions we'll answer with the canned "it is out of domain" reply.
            //
            //    We intentionally do NOT use this RetrievedContext directly for the LLM call,
            //    because progressive prompt shrinking (per OpenRouter 402 "Prompt tokens
            //    limit exceeded") must happen inside the same async task that calls the model.
            //    ChatStreamHandler.streamWithBudgetRetry will re-retrieve with progressively
            //    smaller budgets inside its async scope, guaranteeing PromptTokenLimitException
            //    is caught where it's thrown.
            var probeContext = codeContextRetriever.retrieve(repo.getId(), userContent,
                    RagSettings.TOP_K_CHUNKS, RagSettings.MAX_CHARS_PER_CHUNK);
            if (probeContext.contextText() == null
                    || probeContext.contextText().isBlank()
                    || NO_MATCHES.equals(probeContext.contextText())) {
                return chatStreamHandler.sendFixedReply(
                        session.getId(), savedUserResponse, List.of(), OUT_OF_DOMAIN_REPLY);
            }

            // 5. Delegate to ChatStreamHandler for RAG + model call + budget retries
            //    entirely inside the async SSE executor thread.
            return chatStreamHandler.streamWithBudgetRetry(
                    session.getId(),
                    savedUserResponse,
                    codeContextRetriever,
                    chatPromptBuilder,
                    repo.getId(),
                    repo.getFullName(),
                    userContent);
        } catch (Exception ex) {
            // Delivery via SSE so the frontend sees an in-chat error instead of a 500 toast.
            String safeMsg = "Sorry, I ran into an issue: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return chatStreamHandler.sendFixedReply(
                    session.getId(), savedUserResponse, List.of(), safeMsg);
        }
    }

    /**
     * Determines whether the user's message is a pure greeting that should get the
     * canned repomate greeting reply without an LLM call.
     *
     * <p>Normalizes input by lowercasing and stripping punctuation, then tokenizes
     * by whitespace. Accepts the message if EVERY non-empty token is a known
     * greeting. This matches "hii", "hi!!", "hello there", "hii how are you" would
     * NOT match (presence of words like "how"/"are"/"you" disqualify it — those are
     * real questions the LLM should answer if context allows).
     */
    static boolean isGreeting(String raw) {
        if (raw == null) return false;
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", "")
                .trim();
        if (normalized.isEmpty()) return false;
        String[] tokens = normalized.split("\\s+");
        int matched = 0;
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            if (GREETING_TOKENS.contains(t)) matched++;
            else return false;
        }
        return matched > 0;
    }

    private ChatSessionResponse toSessionResponse(ChatSession session) {
        return new ChatSessionResponse(
                session.getId(),
                session.getRepositoryId(),
                session.getTitle(),
                session.getCreatedAt());
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
