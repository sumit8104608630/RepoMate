package devPilot.backend.services.ai;

public final class RagSettings {
     /** How many code chunks to fetch from the vector database per question. */
    public static final int TOP_K_CHUNKS = 4;

    /** Maximum characters per chunk when building prompt context.
     *  Each chunk is truncated to this length before joining into the prompt,
     *  to prevent INPUT (prompt) token overages even when the vector store
     *  returns large code blocks. The last 400 chars of the head + last 1000 chars
     *  of the tail are preserved so signatures / function start AND ends remain
     *  visible to the model at the cost of middle content.
     */
    public static final int MAX_CHARS_PER_CHUNK = 1800;

    /** Max time (ms) to keep an SSE stream open while the model is responding. */
    public static final long STREAM_TIMEOUT_MS = 180_000L;

    /** Metadata key stored on each embedded document (must match {@link devPilot.backend.service.indexing.CodeChunker}). */
    public static final String METADATA_REPO_ID = "repoId";

    private RagSettings() {
    }
}