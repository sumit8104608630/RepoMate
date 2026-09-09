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
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeContextRetriever {
    private static final String NO_MATCHES = "(no matching code chunks found)";

    private final VectorStore vectorStore;
    private final CitationMapper citationMapper;

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

        log.info("RAG retrieve: repoId={}, question='{}', docCount={}",
                repositoryId,
                question == null ? "<null>" : question.replace('\n', ' ').substring(0, Math.min(80, question.length())),
                documents.size());

        var citations = documents.stream()
                .map(citationMapper::fromDocument)
                .distinct()
                .toList();

        var contextText = documents.stream()
                .map(DocumentTextExtractor::extract)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n---\n\n"));

        log.info("RAG retrieve: docCount={}, citationCount={}, contextTextLength={}, first500chars='{}'",
                documents.size(),
                citations.size(),
                contextText.length(),
                contextText.isEmpty() ? "<EMPTY>" : contextText.substring(0, Math.min(500, contextText.length())).replace('\n', ' '));

        if (documents.isEmpty() || citations.isEmpty() || contextText.isBlank()) {
            log.warn("RAG retrieve short-circuit → out of domain. docCount={}, citationsEmpty={}, contextEmpty={}",
                    documents.size(), citations.isEmpty(), contextText.isBlank());
            return new RetrievedContext(List.of(), NO_MATCHES);
        }

        return new RetrievedContext(citations, contextText);
    }
}