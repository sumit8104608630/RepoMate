package devPilot.backend.services;

import java.util.List;
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

    private static final String GREETING_INPUT = "hii";
    private static final String GREETING_REPLY = "hii iam repomate how ca i help you";
    private static final String OUT_OF_DOMAIN_REPLY = "it is out of domain";

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

        ChatMessageResponse userResp = toMessageResponse(userMessage);

        // 3. Short-circuit: exact greeting (hard constraint)
        if (GREETING_INPUT.equalsIgnoreCase((userContent == null ? "" : userContent).trim())) {
            return chatStreamHandler.sendFixedReply(
                    session.getId(), userResp, List.of(), GREETING_REPLY);
        }

        // 4. Short-circuit: out-of-domain detection (no code-related keywords + no matches)
        if (isOutOfDomain(userContent)) {
            return chatStreamHandler.sendFixedReply(
                    session.getId(), userResp, List.of(), OUT_OF_DOMAIN_REPLY);
        }

        // 5. Stream LLM response WITH progressive budget shrinking.
        //    If a 402 INPUT token limit occurs, streamWithBudgetRetry() will automatically
        //    retry with smaller context windows (topK=3→2→1→0 and chars/chunk shrinking)
        //    before giving up with a user-friendly error.
        return chatStreamHandler.streamWithBudgetRetry(
                session.getId(),
                userResp,
                codeContextRetriever,
                chatPromptBuilder,
                repo.getId(),
                repo.getFullName(),
                userContent);
    }

    private static boolean isOutOfDomain(String userContent) {
        if (userContent == null) return true;
        String q = userContent.trim().toLowerCase();
        if (q.isEmpty()) return true;

        // Short queries (<= 100 chars) are ALWAYS allowed through.
        // Why? Because abbreviated code questions like "what does f do",
        // "explain t", or "bug?" don't contain literal keywords like "class" or "file",
        // but they ARE in-domain once RAG contextualises them.
        // The 7-level budget ladder + zero-RAG fallback handles them safely on 402,
        // so false negatives here have no downside — but false positives (the screenshot
        // showing "what does do f" → "it is out of domain") ruin the UX.
        if (q.length() <= 100) return false;

        String[] codeKeywords = {
                // Core dev terms
                "code", "file", "class", "function", "method", "variable", "bug", "error",
                "line", "fix", "implement", "refactor", "explain", "what does", "how does",
                "where", "find", "search", "repository", "repo", "import", "return",
                "compile", "runtime", "exception", "stack", "debug", "test", "library",
                "package", "module", "config", "setup", "build", "deploy", "api",
                // Question starters / common verbs (long queries without ANY of these are
                // almost never code-related)
                "what", "how", "why", "when", "where", "which", "who",
                "define", "describe", "help", "show", "list", "tell", "mean",
                "can", "could", "should", "would", "will", "do", "does", "is", "are",
                // Code syntax / punctuation hints
                "()", "{", "}", "//", "/*", "*/", ".java", ".ts", ".js", ".py", ".rs",
                ".cpp", ".cs", ".go", ".php", ".rb", ".sql", ".html", ".css",
                // Misc common dev phrases
                "this line", "this function", "this method", "this class", "this file",
                "the error", "the code", "the repo", "crash", "fail", "warning", "null",
                "trace", "log", "console", "terminal", "command", "script", "docker"
        };
        for (String kw : codeKeywords) {
            if (q.contains(kw)) return false;
        }
        return true;
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