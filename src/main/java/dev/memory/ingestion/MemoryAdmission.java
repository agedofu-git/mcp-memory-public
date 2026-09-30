package dev.memory.ingestion;

import dev.memory.domain.*;
import dev.memory.embedding.EmbeddingClient;
import dev.memory.repository.*;
import dev.memory.service.MemoryException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Prepares model decisions without a database transaction, then commits one candidate atomically. */
@Service
public class MemoryAdmission {
    private static final Logger log = LoggerFactory.getLogger(MemoryAdmission.class);
    public record Admission(String action, Memory memory) {}
    private record RelationTarget(Memory memory, MemoryRelation relation, DuplicateDetector.Decision decision) {}
    private final MemoryRepository repository;
    private final MemoryWrites writes;
    private final EmbeddingClient embeddings;
    private final MemoryRelationDetector relations;
    private final DuplicateDetector duplicates;
    public MemoryAdmission(MemoryRepository repository, MemoryWrites writes, EmbeddingClient embeddings,
                           MemoryRelationDetector relations, DuplicateDetector duplicates) {
        this.repository = repository; this.writes = writes; this.embeddings = embeddings; this.relations = relations; this.duplicates = duplicates;
    }
    public Admission admit(MemoryDraft candidate) {
        EmbeddingClient.Embedding embedding = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                long generation = writes.generation();
                var exact = repository.exact(candidate.type(), candidate.content(), (String) candidate.metadata().get("project"));
                if (exact.isPresent()) return writes.commit(generation, () -> rememberDuplicate(candidate, exact.get(), false));
                if (embedding == null) embedding = embeddings.embed(candidate.content());
                return decideAndCommit(candidate, embedding, generation);
            } catch (MemoryException exception) {
                if (exception.kind() != MemoryException.Kind.CONFLICT || attempt == 1) throw exception;
                log.info("event=memory_admission_retry reason=concurrent_change");
            }
        }
        throw new IllegalStateException("Unreachable admission retry state");
    }

    private Admission decideAndCommit(MemoryDraft candidate, EmbeddingClient.Embedding embedding, long generation) {
        var targets = new ArrayList<RelationTarget>();
        for (var hit : repository.related(candidate, embedding)) {
            var classification = relations.classify(candidate, hit.memory());
            var decision = duplicates.decide(candidate, hit.memory(), classification.relation(), hit.semantic());
            targets.add(new RelationTarget(hit.memory(), classification.relation(), decision));
        }
        // Stable survivor selection makes the final state independent of retrieval order.
        targets.sort(Comparator.comparing((RelationTarget target) -> target.memory().createdAt())
                .thenComparing(target -> target.memory().id()));
        var matching = targets.stream().filter(target -> target.decision() == DuplicateDetector.Decision.IGNORE
                || target.decision() == DuplicateDetector.Decision.MERGE).toList();
        var survivor = matching.isEmpty() ? null : matching.getFirst();
        var superseded = targets.stream().filter(target -> target.decision() == DuplicateDetector.Decision.SUPERSEDE
                || (matching.contains(target) && target != survivor)).toList();
        var contradictions = targets.stream().filter(target -> target.decision() == DuplicateDetector.Decision.LINK_CONTRADICTION).toList();
        return writes.commit(generation, () -> {
            for (var target : superseded) repository.supersede(target.memory(), "SUPERSEDED");
            if (survivor != null) repository.requireActive(List.of(survivor.memory().id()));
            Admission duplicate = survivor == null ? null : rememberDuplicate(candidate, survivor.memory(),
                    matching.stream().anyMatch(target -> target.decision() == DuplicateDetector.Decision.MERGE));
            var memory = duplicate == null
                    ? repository.insert(candidate, embedding, superseded.isEmpty() ? null : superseded.getFirst().memory().id(), null, null)
                    : duplicate.memory();
            for (var target : superseded) {
                repository.link(memory.id(), target.memory().id(), "SUPERSEDES");
                if (target.relation() == MemoryRelation.REFINEMENT) repository.link(memory.id(), target.memory().id(), "REFINES");
            }
            for (var target : contradictions) repository.link(memory.id(), target.memory().id(), "CONTRADICTS");
            String action = !superseded.isEmpty() ? "SUPERSEDE" : !contradictions.isEmpty() ? "CONTRADICTION"
                    : duplicate == null ? "CREATE" : duplicate.action();
            log.info("event=memory_admitted action={} memoryId={}", action, memory.id());
            return new Admission(action, memory);
        });
    }
    private Admission rememberDuplicate(MemoryDraft candidate, Memory existing, boolean merge) {
        repository.evidence(existing.id(), candidate);
        var memory = existing;
        if (merge) {
            var tags = new LinkedHashSet<>(existing.tags()); tags.addAll(candidate.tags());
            var draft = new MemoryDraft(existing.type(), existing.content(), Math.max(existing.importance(), candidate.importance()),
                    Math.max(existing.confidence(), candidate.confidence()), tags.stream().limit(20).toList(), existing.metadata(),
                    existing.source(), existing.sourceConversationId(), null);
            memory = repository.replace(existing, draft, null, existing.archived());
        }
        log.info("event=duplicate_detected memoryId={} merged={}", memory.id(), merge);
        return new Admission(merge ? "MERGE" : "IGNORE", memory);
    }
}
