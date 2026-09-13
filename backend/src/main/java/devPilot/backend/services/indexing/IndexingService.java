package devPilot.backend.services.indexing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {
    private static final int VECTOR_BATCH_SIZE = 16;
    private static final int PROGRESS_EVERY_N_FILES = 5;

    private static final Duration TOTAL_INDEXING_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration PER_FILE_TIMEOUT = Duration.ofMinutes(2);

    private final RepositoryRepository repositoryRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final VectorStore vectorStore;

    private final Map<UUID, Boolean> cancelledRepos = new ConcurrentHashMap<>();

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    public Repository startIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is already being indexed");
        }

        cancelledRepos.remove(repoId);
        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    public void cancelIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        // Request cancellation for any in-flight async task (polled inside doIndex loop).
        cancelledRepos.put(repoId, Boolean.TRUE);

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            // Immediately flip the DB state so the UI no longer shows "Indexing...".
            // If the async thread is still alive, it will see the flag and short-circuit
            // within one loop iteration (max 60s due to the read timeout) instead of
            // staying stuck for hours.
            repo.setIndexStatus(IndexStatus.CANCELLED);
            repo.setErrorMessage("Indexing cancelled by user");
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
            log.info("cancelIndexing: user requested cancel for repo {}", repoId);
        }
    }

    @Async("indexingExecutor")
    public void indexAsync(UUID repoId, UUID userId) {
        try {
            doIndex(repoId, userId);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        } finally {
            cancelledRepos.remove(repoId);
        }
    }

    private boolean isCancelled(UUID repoId) {
        return Boolean.TRUE.equals(cancelledRepos.get(repoId));
    }

    private static boolean isFatalQuotaError(Throwable t) {
        if (t == null) return false;
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage() == null ? "" : cur.getMessage();
            if (msg.contains("Daily/Long-Quota 429")
                    || msg.contains("openrouter_free_tier_daily")
                    || msg.contains("free-models-per-day")) {
                return true;
            }
        }
        return false;
    }

    private static String formatDuration(Duration d) {
        if (d == null) return "0s";
        long s = d.toSeconds();
        if (s < 60) return s + "s";
        return String.format("%dm%02ds", s / 60, s % 60);
    }

    private static RuntimeException wrapFatalIfQuota(Throwable t) {
        String msg = "OpenRouter daily free-model quota (50 requests/day) is exhausted. "
                + "Indexing cannot continue until the daily reset or until you add credits.\n"
                + "  Quick fix: add $10 credits at https://openrouter.ai/settings/credits to unlock 1000 free model requests/day.\n"
                + "  Alternative: configure a paid embedding model via spring.ai.openai.embedding.options.model\n"
                + "  Root cause: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        return new RuntimeException(msg, t);
    }

      private void doIndex(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        String token = userService.decryptAccessToken(userService.requiredById(userId));

        Instant start = Instant.now();

        // If the repo was cancelled BEFORE the async task even started running
        // (e.g. user clicked Cancel 200ms after clicking Index), bail immediately.
        if (isCancelled(repoId)) {
            log.info("doIndex: repo {} already cancelled before start", repoId);
            return;
        }

        deleteExistingVectors(repoId.toString());

        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        List<String> filePaths = listIndexableFiles(tree);

        updateProgress(repoId, filePaths.size(), 0, 0, IndexStatus.INDEXING, null);

        List<Document> batch = new ArrayList<>();
        int processed = 0;
        int totalChunks = 0;
        Instant lastIterationStart = Instant.now();

        for (String path : filePaths) {
            // Cooperative cancellation check — every file.
            if (isCancelled(repoId)) {
                log.info("doIndex: repo {} cancelled at file {}/{}; stopping.",
                        repoId, processed, filePaths.size());
                return;
            }

            // Total-time watchdog: bail if the whole index run is taking >20min.
            Duration totalElapsed = Duration.between(start, Instant.now());
            if (totalElapsed.compareTo(TOTAL_INDEXING_TIMEOUT) > 0) {
                String msg = "Indexing timed out: total elapsed " + formatDuration(totalElapsed)
                        + " exceeds limit " + formatDuration(TOTAL_INDEXING_TIMEOUT)
                        + " after " + processed + "/" + filePaths.size() + " files."
                        + " Try indexing a smaller repository or increase credits.";
                markFailed(repoId, msg);
                return;
            }

            // Per-file watchdog: if a SINGLE file iteration took >2min (should be impossible
            // now that we have 60s read timeout, but belt-and-suspenders), treat as deadlock.
            Duration perFile = Duration.between(lastIterationStart, Instant.now());
            if (perFile.compareTo(PER_FILE_TIMEOUT) > 0) {
                String msg = "Indexing stuck on file '" + path + "': file iteration took "
                        + formatDuration(perFile) + " (limit " + formatDuration(PER_FILE_TIMEOUT) + ").";
                markFailed(repoId, msg);
                return;
            }
            lastIterationStart = Instant.now();

            // Two separate try/catch blocks so GitHub fetch failures != embedding failures.
            List<Document> chunks = List.of();
            try {
                String content = gitHubApiClient.getFileContent(
                        token, repo.getOwner(), repo.getName(), path);
                // Pause ONLY after a successful GitHub API content fetch.
                rateLimiter.pause();
                chunks = codeChunker.chunkFile(repoId.toString(), path, content);
                batch.addAll(chunks);
                totalChunks += chunks.size();
            } catch (Exception ex) {
                // GitHub fetch / local chunking failure: skip this single file and move on.
                log.warn("Skipping file {} in {}: {}", path, repo.getFullName(), ex.getMessage());
            }

            try {
                if (batch.size() >= VECTOR_BATCH_SIZE) {
                    vectorStore.add(batch);
                    batch.clear();
                }
            } catch (RuntimeException ex) {
                if (isFatalQuotaError(ex)) throw wrapFatalIfQuota(ex);
                // Non-fatal embedding error: log it, drop the batch to avoid infinite re-send, continue.
                log.warn("Dropping embedding batch (size={}) for repo {} due to non-quota error: {}",
                        batch.size(), repoId, ex.getMessage());
                batch.clear();
            }

            processed++;
            if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
                updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
            }
        }

        if (!batch.isEmpty()) {
            try {
                vectorStore.add(batch);
            } catch (RuntimeException ex) {
                if (isFatalQuotaError(ex)) throw wrapFatalIfQuota(ex);
                log.warn("Dropping final embedding batch (size={}) for repo {} due to non-quota error: {}",
                        batch.size(), repoId, ex.getMessage());
            }
        }

        // Final cancellation check before marking READY — user may have cancelled
        // right after the last loop iteration.
        if (isCancelled(repoId)) {
            log.info("doIndex: repo {} cancelled before marking READY; skipping.", repoId);
            return;
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
    };

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

      @Transactional
    protected void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName) {
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