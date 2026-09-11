package devPilot.backend.services.ai;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CodeContextRetriever {
    private static final String NO_MATCHES = "(no matching code chunks found)";

    private final VectorStore vectorStore;
    private final CitationMapper citationMapper; 

    /** Default retrieve with TOP_K_CHUNKS + MAX_CHARS_PER_CHUNK limits. */
    public RetrievedContext retrieve(UUID repositoryId, String question) {
        return retrieve(repositoryId, question, RagSettings.TOP_K_CHUNKS, RagSettings.MAX_CHARS_PER_CHUNK);
    }

    /**
     * Customisable retrieve. Callers (e.g. ChatService) that hit {@code PromptTokenLimitException}
     * can progressively shrink topK and maxCharsPerChunk to fit the user's remaining credit budget.
     *
     * @param topK maximum number of code chunks to include
     * @param maxCharsPerChunk maximum characters to keep from any single chunk (head+tail preserved)
     */
    public RetrievedContext retrieve(UUID repositoryId, String question, int topK, int maxCharsPerChunk) {
        var filter = new FilterExpressionBuilder()
                .eq(RagSettings.METADATA_REPO_ID, repositoryId.toString())
                .build();

        var search = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .filterExpression(filter)
                .build();

        var documents = vectorStore.similaritySearch(search);

        var citations = documents.stream()
                .map(citationMapper::fromDocument)
                .distinct()
                .toList();

        var contextText = documents.stream()
                .map(d -> truncateChunk(d.getText(), maxCharsPerChunk))
                .collect(Collectors.joining("\n\n---\n\n"));

        if (contextText.isBlank()) {
            contextText = NO_MATCHES;
        }

        return new RetrievedContext(citations, contextText);
    }

    /**
     * Truncates a large code chunk down to {@code maxChars} while preserving the
     * head (first 40%) and tail (last 60%) of the content so imports/signatures AND
     * closing braces / return values stay visible to the model. Middle content is
     * replaced with a truncated marker, which the system prompt teaches the LLM to
     * understand.
     */
    static String truncateChunk(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        int headChars = Math.max(1, maxChars * 2 / 5);
        int tailChars = Math.max(1, maxChars - headChars);
        String head = text.substring(0, headChars);
        String tail = text.substring(text.length() - tailChars);
        return head + "\n\n... [chunk truncated; " + (text.length() - maxChars)
                + " chars omitted for prompt-size limit] ...\n\n" + tail;
    }
}