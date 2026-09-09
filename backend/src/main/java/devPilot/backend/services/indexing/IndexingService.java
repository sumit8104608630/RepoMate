package devPilot.backend.services.indexing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.UserService;
import devPilot.backend.services.ai.RagSettings;
import devPilot.backend.services.github.GitHubRateLimiter;
import devPilot.backend.services.github.GithubApiClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {
    private static final int VECTOR_BATCH_SIZE = 32;
    private static final int PROGRESS_EVERY_N_FILES = 5;

    private final RepositoryRepository repositoryRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    public Repository startIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is already being indexed");
        }

        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    @Async("indexingExecutor")
    public void indexAsync(UUID repoId, UUID userId) {
        try {
            doIndex(repoId, userId);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        }
    }

    private void doIndex(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        String token = userService.decryptAccessToken(userService.requiredById(userId));

        deleteExistingVectors(repoId.toString());

        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        List<String> filePaths = listIndexableFiles(tree);

        updateProgress(repoId, filePaths.size(), 0, 0, IndexStatus.INDEXING, null);

        List<Document> batch = new ArrayList<>();
        int processed = 0;
        int totalChunks = 0;

        for (String path : filePaths) {
            try {
                String content = gitHubApiClient.getFileContent(
                        token, repo.getOwner(), repo.getName(), path);

                if (content == null || content.isBlank()) {
                    processed++;
                    if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
                        updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
                    }
                    rateLimiter.pause();
                    continue;
                }

                // Postgres UTF8 columns reject null bytes (0x00). Files containing them
                // are typically binary/corrupted and slipped past the extension filter —
                // skip rather than crash the whole batch insert.
                if (content.indexOf('\u0000') >= 0) {
                    log.warn("Skipping file {} in {}: contains null bytes (likely binary)",
                            path, repo.getFullName());
                    processed++;
                    if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
                        updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
                    }
                    rateLimiter.pause();
                    continue;
                }

                List<Document> chunks = codeChunker.chunkFile(repoId.toString(), path, content);
                batch.addAll(chunks);
                totalChunks += chunks.size();
                if (batch.size() >= VECTOR_BATCH_SIZE) {
                    persistBatch(batch);
                    batch.clear();
                }
            } catch (Exception ex) {
                log.warn("Skipping file {} in {}: {}", path, repo.getFullName(), ex.getMessage());
            }

            processed++;
            if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
                updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
            }
            rateLimiter.pause();
        }

        if (!batch.isEmpty()) {
            persistBatch(batch);
        }

        markReady(repoId, filePaths.size(), processed, totalChunks, repo.getFullName());
    }

    @SuppressWarnings("unchecked")
    private List<String> listIndexableFiles(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        return entries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return fileFilter.isEligible(path, size, maxFileBytes);
                })
                .map(entry -> String.valueOf(entry.get("path")))
                .toList();
    }

    private void deleteExistingVectors(String repoId) {
        try {
            var filter = new FilterExpressionBuilder().eq(RagSettings.METADATA_REPO_ID, repoId).build();
            vectorStore.delete(filter);
        } catch (Exception ex) {
            log.warn("Could not delete existing vectors for repo {}: {}", repoId, ex.getMessage());
        }
    }

    @Transactional
    protected void updateProgress(
            UUID repoId,
            int total,
            int processed,
            int chunks,
            IndexStatus status,
            String error) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setFilesTotal(total);
            repo.setFilesProcessed(processed);
            repo.setChunkCount(chunks);
            repo.setIndexStatus(status);
            repo.setErrorMessage(error);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

    private void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
        log.info("Indexed {} files ({} chunks) for {}", processedFiles, totalChunks, fullName);
    }

    /**
     * Writes a batch of Document chunks to the VectorStore and immediately
     * verifies via direct JDBC that the TEXT content column is not NULL for
     * at least one of the inserted IDs. If content is NULL for all rows we
     * fail fast with a clear log message — otherwise the user would only
     * discover this later as a silent "out of domain" at chat time.
     */
    private void persistBatch(List<Document> batch) {
        if (batch == null || batch.isEmpty()) return;

        List<String> preIds = batch.stream()
                .map(Document::getId)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();

        vectorStore.add(batch);

        List<String> ids = preIds.isEmpty()
                ? batch.stream().map(Document::getId).filter(java.util.Objects::nonNull).map(Object::toString).toList()
                : preIds;

        if (ids.isEmpty()) {
            log.warn("persistBatch: VectorStore returned no Document IDs; cannot verify content column via JDBC.");
            return;
        }

        try {
            String placeholders = ids.stream().map(i -> "?").collect(java.util.stream.Collectors.joining(","));
            String sql = "SELECT COUNT(*) AS n, COUNT(content) AS with_content " +
                         "FROM public.vector_store WHERE id IN (" + placeholders + ")";
            jdbcTemplate.query(sql, rs -> {
                int n = rs.getInt(1);
                int withContent = rs.getInt(2);
                if (n == 0) {
                    log.error("persistBatch: POST-INSERT VERIFICATION FAILED. vectorStore.add() returned, " +
                            "but JDBC SELECT found 0 rows for the {} IDs we asked for. " +
                            "Row mapping / id-column-name mismatch. Content will be lost.", ids.size());
                    throw new RuntimeException("VectorStore persistBatch verification: rows not found after add()");
                }
                if (withContent == 0) {
                    log.error(
                        "================================================================================\n" +
                        "persistBatch: POST-INSERT VERIFICATION FAILED.\n" +
                        "  Rows inserted: {} / {}\n" +
                        "  Rows with non-NULL content column: **0**\n" +
                        "This means PgVectorStore is writing embedding + metadata but NOT the content TEXT column.\n" +
                        "Most likely cause: content-column-name mismatch between the migration and Spring AI autoconfig.\n" +
                        "Ensure spring.ai.vectorstore.pgvector.content-column-name=content matches the schema.\n" +
                        "================================================================================\n",
                        n, ids.size());
                    throw new RuntimeException("VectorStore content column is NULL after insert — check column mapping.");
                }
                double pct = 100.0 * withContent / n;
                log.info("persistBatch: verified {} / {} rows ({}) with non-NULL content column.",
                        withContent, n, String.format("%.1f%%", pct));
            }, ids.toArray());
        } catch (RuntimeException rtex) {
            throw rtex;
        } catch (Exception ex) {
            log.warn("persistBatch: post-write verification query failed (non-fatal): {}", ex.toString());
        }
    }

    @Transactional
    protected void markFailed(UUID repoId, String message) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage(message != null && message.length() > 2000
                    ? message.substring(0, 2000)
                    : message);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

}