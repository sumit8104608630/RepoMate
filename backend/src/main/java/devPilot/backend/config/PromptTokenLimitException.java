package devPilot.backend.config;

/**
 * Thrown when OpenRouter returns 402 with "Prompt tokens limit exceeded", indicating
 * the COMBINED INPUT (system prompt + RAG context + user question) is too large for the
 * user's remaining credit balance. This is distinguishable from an OUTPUT (max_tokens)
 * 402, because retrying with smaller max_tokens does NOT help — we must instead retry
 * with a SHORTER prompt (e.g. fewer/smaller RAG chunks, truncated context).
 *
 * <p>Callers should catch this specific exception and progressively shrink the input:
 * drop the lowest-similarity RAG chunk(s), truncate existing chunks, strip optional
 * prompt preamble, etc. until the call succeeds or we fall back to a canned reply.</p>
 */
public class PromptTokenLimitException extends RuntimeException {
    public PromptTokenLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
