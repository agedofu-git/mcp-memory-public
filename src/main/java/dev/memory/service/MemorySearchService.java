package dev.memory.service;

import dev.memory.config.MemoryProperties;
import dev.memory.controller.Requests.Search;
import dev.memory.embedding.EmbeddingClient;
import dev.memory.ranking.MemoryRanker;
import dev.memory.repository.MemoryRepository;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MemorySearchService {
    private static final Logger log = LoggerFactory.getLogger(MemorySearchService.class);
    private final EmbeddingClient embeddings;
    private final MemoryRepository repository;
    private final MemoryRanker ranker;
    private final int candidatePool;
    private final MemoryProperties.Ranking settings;
    public MemorySearchService(EmbeddingClient embeddings, MemoryRepository repository, MemoryRanker ranker, MemoryProperties properties) {
        this.embeddings = embeddings; this.repository = repository; this.ranker = ranker; candidatePool = properties.ranking().candidatePool();
        settings = properties.ranking();
    }
    public List<MemoryRanker.Result> search(Search search) {
        if (search.createdFrom() != null && search.createdTo() != null && search.createdFrom().isAfter(search.createdTo()))
            throw new MemoryException(MemoryException.Kind.INVALID_REQUEST, "createdFrom must precede createdTo.");
        var embedding = embeddings.embed(search.query());
        var results = repository.search(search, embedding, Math.max(candidatePool, search.effectiveLimit())).stream()
                .filter(hit -> hit.semantic() >= settings.minimumSemanticScore())
                .map(hit -> ranker.rank(hit, search.preferredType(), search.debug()))
                .filter(result -> result.score() >= settings.minimumScore())
                .sorted(Comparator.comparingDouble(MemoryRanker.Result::score).reversed().thenComparing(result -> result.memory().id()))
                .limit(search.effectiveLimit()).toList();
        if (search.debug()) results.forEach(result -> log.debug("event=retrieval_score memoryId={} score={} components={}",
                result.memory().id(), result.score(), result.components()));
        return results;
    }
}
