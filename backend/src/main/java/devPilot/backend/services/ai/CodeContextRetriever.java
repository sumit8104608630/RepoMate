package devPilot.backend.services.ai;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeContextRetriever {
    private static final String NO_MATCHES = "(no matching code chunks found)";

    private final VectorStore vectorStore;
    private final CitationMapper citationMapper;
    private final JdbcTemplate jdbcTemplate;

    public RetrievedContext retrieve(UUID repositoryId, String question) {
        var filter = new FilterExpressionBuilder()
                .eq(RagSettings.METADATA_REPO_ID, repositoryId.toString())
                .build();

        var search = SearchRequest.builder()
                .query(question)
                .topK(RagSettings.TOP_K_CHUNKS)
                .filterExpression(filter)
                .build();

        var documents = vectorStore.similaritySearch(search);

        String shortQ = question == null ? "<null>" : question.replace('\n', ' ').substring(0, Math.min(80, question.length()));
        log.info("RAG retrieve: repoId={}, question='{}', docCount={}", repositoryId, shortQ, documents.size());

        // Step 1 — build a map of Document.id -> raw content from Postgres via JDBC.
        // This is the GOLDEN SOURCE: whatever PgVectorStore did with its accessors,
        // the TEXT column in the DB table is the actual content.
        Map<UUID, String> directContentMap = loadContentDirect(documents);

        var citations = documents.stream()
                .map(citationMapper::fromDocument)
                .distinct()
                .toList();

        // Step 2 — For each Document, prefer direct JDBC content; fall back to
        // DocumentTextExtractor (which has all the reflection paths + diagnostics).
        StringBuilder sb = new StringBuilder();
        int directHits = 0;
        int extractorHits = 0;
        int empty = 0;
        for (Document doc : documents) {
            UUID docId = doc.getId() == null ? null : safeUuid(doc.getId());
            String direct = docId == null ? "" : directContentMap.getOrDefault(docId, "");
            String text = !direct.isBlank() ? direct : DocumentTextExtractor.extract(doc);
            if (!direct.isBlank()) directHits++;
            else if (!text.isBlank()) extractorHits++;
            else empty++;
            if (!text.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n---\n\n");
                sb.append(text);
            }
        }
        String contextText = sb.toString();

        String preview = contextText.isEmpty() ? "<EMPTY>" : contextText.substring(0, Math.min(600, contextText.length())).replace('\n', ' ');
        log.info("RAG retrieve stats: docCount={}, citations={}, directHits={}, extractorHits={}, empty={}, contextLen={}, preview='{}'",
                documents.size(), citations.size(), directHits, extractorHits, empty, contextText.length(), preview);

        if (documents.isEmpty() || citations.isEmpty() || contextText.isBlank()) {
            log.warn("RAG retrieve → out of domain. docCount={}, citationsEmpty={}, contextEmpty={}",
                    documents.size(), citations.isEmpty(), contextText.isBlank());
            return new RetrievedContext(List.of(), NO_MATCHES);
        }

        return new RetrievedContext(citations, contextText);
    }

    // ------------------------------------------------------------------ helpers

    private Map<UUID, String> loadContentDirect(List<Document> documents) {
        Map<UUID, String> out = new HashMap<>();
        if (documents == null || documents.isEmpty()) return out;
        List<UUID> ids = documents.stream()
                .map(Document::getId)
                .map(this::safeUuid)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return out;
        try {
            String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
            String sql = "SELECT id, content FROM public.vector_store WHERE id IN (" + placeholders + ")";
            jdbcTemplate.query(sql, rs -> {
                UUID id = (UUID) rs.getObject(1);
                String content = rs.getString(2);
                if (id != null && content != null && !content.isBlank()) {
                    out.put(id, content);
                }
            }, ids.toArray());
            log.debug("Direct JDBC vector_store read: requested={}, gotNonEmptyContent={}", ids.size(), out.size());
        } catch (Exception ex) {
            log.warn("Direct JDBC vector_store fallback failed ({}). Falling back to Document.getText paths.", ex.toString());
        }
        return out;
    }

    private UUID safeUuid(Object idVal) {
        if (idVal == null) return null;
        if (idVal instanceof UUID u) return u;
        String s = idVal.toString().trim();
        if (s.isEmpty()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }
}
