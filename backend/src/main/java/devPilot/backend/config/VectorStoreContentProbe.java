package devPilot.backend.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs once at boot: samples rows from vector_store to verify the content
 * column is NOT globally NULL. If 100% of sampled rows have NULL/empty
 * content, the RAG pipeline cannot answer questions and the index must
 * be rebuilt with the corrected column mapping.
 */
@Component
public class VectorStoreContentProbe implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreContentProbe.class);

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreContentProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        try {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.vector_store", Integer.class);
            if (total == null || total == 0) {
                log.info("VectorStoreProbe: vector_store is empty — no repositories indexed yet. Normal on fresh install.");
                return;
            }

            List<String> samples = jdbcTemplate.query(
                    "SELECT content FROM public.vector_store LIMIT 20",
                    (rs, rowNum) -> rs.getString(1));

            int nonEmpty = 0;
            int nullCount = 0;
            int emptyBlank = 0;
            long totalChars = 0L;
            String firstNonEmptyPreview = null;
            for (String c : samples) {
                if (c == null) { nullCount++; continue; }
                if (c.isBlank()) { emptyBlank++; continue; }
                nonEmpty++;
                totalChars += c.length();
                if (firstNonEmptyPreview == null) {
                    firstNonEmptyPreview = c.substring(0, Math.min(240, c.length())).replace('\n', ' ');
                }
            }
            int sampleSize = samples.size();
            double pctNonEmpty = sampleSize == 0 ? 0 : 100.0 * nonEmpty / sampleSize;
            log.info("VectorStoreProbe: totalRows={}, sampledRows={}, withContent={} ({}%), nullRows={}, blankRows={}, avgContentLen={} chars",
                    total, sampleSize, nonEmpty, String.format("%.1f", pctNonEmpty),
                    nullCount, emptyBlank, nonEmpty == 0 ? 0 : (totalChars / nonEmpty));
            if (firstNonEmptyPreview != null) {
                log.info("VectorStoreProbe: FIRST NON-EMPTY CONTENT PREVIEW: {}", firstNonEmptyPreview);
            }
            if (nonEmpty == 0 && sampleSize > 0) {
                log.error(
                    "================================================================================\n" +
                    "VectorStoreProbe: ALL {} sampled rows have NULL/empty content column!\n" +
                    "This means the content was NEVER WRITTEN to vector_store.content during indexing.\n" +
                    "RAG will ALWAYS say 'it is out of domain'. FIX:\n" +
                    "  1. Make sure spring.ai.vectorstore.pgvector.content-column-name=content is set.\n" +
                    "  2. RE-INDEX every repository (delete old vectors, re-index).\n" +
                    "================================================================================\n",
                    sampleSize);
            } else if (pctNonEmpty < 50) {
                log.warn("VectorStoreProbe: <50% of sampled rows have content ({}%). Indexing may be partially failing. " +
                        "Consider re-indexing affected repos.", String.format("%.1f", pctNonEmpty));
            }
        } catch (Exception ex) {
            log.warn("VectorStoreProbe: could not query vector_store (table may not exist yet, or DB unavailable): {}",
                    ex.toString());
        }
    }
}
