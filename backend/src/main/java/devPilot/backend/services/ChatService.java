package devPilot.backend.services;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
import lombok.RequiredArgsConstructor;

/**
 * Chat sessions and the RAG chat pipeline entry point.
 *
 * <p>{@link #streamReply} orchestrates the full flow:
 * validate → save user message → retrieve code context → build prompts → stream AI reply.
 * Each step is implemented in a dedicated class under {@code service.ai}.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    /** Exact greeting response the user requires. */
    private static final String GREETING_REPLY = "hii i am repomate how can i help you";

    /** Exact out-of-domain response the user requires. */
    private static final String OUT_OF_DOMAIN_REPLY = "it is out of domain";

    /**
     * Normalized greeting tokens. Matches hi, hii, hello, hey, heya, yo, sup, good morning,
     * good afternoon, good evening, etc (case-insensitive). Users often type "hii" specifically.
     */
    private static final Set<String> GREETING_WORDS = Set.of(
            "hi", "hii", "hiii", "hello", "helo", "hallo",
            "hey", "heyy", "heya", "yo", "yoo", "sup",
            "morning", "afternoon", "evening",
            "greetings", "howdy", "namaste", "hiya");

    /** Strip everything except letters + whitespace before testing greeting intent. */
    private static final Pattern NON_LETTER = Pattern.compile("[^a-z\\s]+");

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RepoService repoService;
    private final CodeContextRetriever codeContextRetriever;
    private final ChatPromptBuilder chatPromptBuilder;
    private final ChatStreamHandler chatStreamHandler;
    private final CitationMapper citationMapper;

    private static boolean isGreeting(String userContent) {
        if (userContent == null) return false;
        String normalized = NON_LETTER.matcher(userContent.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (normalized.isEmpty()) return false;
        // "good morning" / "good afternoon" / "good evening"
        if (normalized.startsWith("good ") && normalized.length() > 5) {
            String tail = normalized.substring(5).trim();
            if (GREETING_WORDS.contains(tail)) return true;
        }
        String[] tokens = normalized.split("\\s+");
        int matches = 0;
        int nonMatches = 0;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (GREETING_WORDS.contains(token)) {
                matches++;
            } else if (token.length() >= 2) {
                nonMatches++;
            }
        }
        // treat as greeting if there is at least one greeting word and no meaningful non-greeting words
        return matches > 0 && nonMatches == 0;
    }

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

        // 3. Short-circuit: pure greetings get a deterministic canned reply (no LLM, no RAG)
        if (isGreeting(userContent)) {
            return chatStreamHandler.sendFixedReply(
                    session.getId(), savedUserResponse, List.of(), GREETING_REPLY);
        }

        // 4. RAG retrieval — find code chunks similar to the question
        var retrievedContext = codeContextRetriever.retrieve(repo.getId(), userContent);

        // 5. Short-circuit: no relevant chunks in vector store → treat as out of domain.
        //    This prevents the LLM from seeing "(no matching code chunks found)" in context
        //    and possibly flaking on the required exact phrase.
        if (retrievedContext.citations() == null || retrievedContext.citations().isEmpty()) {
            return chatStreamHandler.sendFixedReply(
                    session.getId(), savedUserResponse, List.of(), OUT_OF_DOMAIN_REPLY);
        }

        // 6. Build LLM prompts from retrieved context + question
        String systemPrompt = chatPromptBuilder.systemPrompt(repo.getFullName());
        String userPrompt = chatPromptBuilder.userPrompt(retrievedContext.contextText(), userContent);

        // 7. Stream OpenRouter response to the client (SSE). If the LLM still decides the
        //    context is insufficient, the updated system prompt forces it to say the exact
        //    phrase "It is out of domain." instead of "I am unsure.".
        return chatStreamHandler.stream(
                session.getId(),
                savedUserResponse,
                retrievedContext.citations(),
                systemPrompt,
                userPrompt);
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