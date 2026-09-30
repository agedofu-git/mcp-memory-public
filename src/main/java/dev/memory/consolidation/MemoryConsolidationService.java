package dev.memory.consolidation;

import dev.memory.config.MemoryProperties;
import dev.memory.domain.*;
import dev.memory.embedding.EmbeddingClient;
import dev.memory.llm.StructuredLlm;
import dev.memory.ranking.MemoryDecayPolicy;
import dev.memory.repository.*;
import dev.memory.service.MemoryException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class MemoryConsolidationService {
    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);
    public record Summary(@NotNull MemoryType type, @NotBlank @Size(max = 8000) String content,
                          @NotNull @DecimalMin("0") @DecimalMax("1") Double importance,
                          @NotNull @DecimalMin("0") @DecimalMax("1") Double confidence,
                          @NotNull @Size(max = 20) List<@NotBlank @Size(max = 60) String> tags,
                          @NotNull @Size(min = 2, max = 20) List<@NotNull UUID> sourceIds) {}
    public record Relation(@NotNull UUID olderId, @NotNull UUID newerId, @NotNull MemoryRelation relation,
                           @NotBlank @Size(max = 1000) String reason) {}
    public record Plan(@NotNull @Size(max = 3) List<@NotNull @Valid Summary> summaries,
                       @NotNull @Size(max = 30) List<@NotNull @Valid Relation> relations) {}
    public record Result(boolean complete, int inspected, List<UUID> summaries, int relations, int archived, List<String> errors) {}
    private record Prepared(Summary summary, MemoryDraft draft, EmbeddingClient.Embedding embedding, Memory existing) {}
    private record Applied(List<UUID> summaries, int relations) {}
    private final MemoryRepository repository;
    private final MemoryWrites writes;
    private final StructuredLlm llm;
    private final EmbeddingClient embeddings;
    private final MemoryDecayPolicy decay;
    private final MemoryProperties.Consolidation settings;
    private final Clock clock;

    public MemoryConsolidationService(MemoryRepository repository, MemoryWrites writes, StructuredLlm llm,
                                      EmbeddingClient embeddings, MemoryDecayPolicy decay, MemoryProperties properties, Clock clock) {
        this.repository = repository; this.writes = writes; this.llm = llm; this.embeddings = embeddings;
        this.decay = decay; settings = properties.consolidation(); this.clock = clock;
    }

    public Result consolidate(boolean archiveStale) {
        var visited = new LinkedHashSet<UUID>();
        var summaries = new ArrayList<UUID>();
        var errors = new ArrayList<String>();
        int relationCount = 0;
        for (var seed : repository.consolidationBatch()) {
            if (visited.size() >= settings.batchSize()) break;
            if (visited.contains(seed.id())) continue;
            try {
                long generation = writes.generation();
                var current = repository.require(seed.id());
                if (!current.active()) { visited.add(seed.id()); continue; }
                var cluster = new ArrayList<Memory>(); cluster.add(current);
                repository.cluster(current).stream().filter(memory -> !visited.contains(memory.id()))
                        .limit(settings.batchSize() - visited.size() - 1L).forEach(cluster::add);
                cluster.forEach(memory -> visited.add(memory.id()));
                if (cluster.size() < 2) {
                    writes.commit(generation, () -> { repository.markConsolidated(List.of(current.id())); return null; });
                    continue;
                }
                var knownContradictions = repository.contradictions(cluster.stream().map(Memory::id).toList());
                var plan = llm.call("memory-consolidation", Map.of("memories", cluster,
                        "knownContradictions", knownContradictions), Plan.class);
                validatePlan(plan, cluster, knownContradictions);
                var prepared = new ArrayList<Prepared>();
                for (var summary : plan.summaries()) {
                    var sources = cluster.stream().filter(memory -> summary.sourceIds().contains(memory.id())).toList();
                    double confidence = Math.min(summary.confidence(), sources.stream().mapToDouble(Memory::confidence).min().orElseThrow());
                    // A summary cannot fabricate higher usefulness than all its sources combined.
                    double importance = Math.min(summary.importance(), sources.stream().mapToDouble(Memory::importance).max().orElseThrow());
                    Map<String, Object> metadata = current.metadata().containsKey("project") ? Map.of("project", current.metadata().get("project")) : Map.of();
                    var draft = new MemoryDraft(summary.type(), summary.content(), importance, confidence, summary.tags(), metadata, "CONSOLIDATED", null, null);
                    var existing = repository.exact(draft.type(), draft.content(), (String) metadata.get("project")).orElse(null);
                    prepared.add(new Prepared(summary, draft, existing == null ? embeddings.embed(draft.content()) : null, existing));
                }
                var applied = writes.commit(generation, () -> apply(plan, prepared, cluster));
                summaries.addAll(applied.summaries()); relationCount += applied.relations();
            } catch (MemoryException exception) {
                errors.add(exception.kind().name());
            } catch (DataAccessException exception) {
                errors.add("DATABASE_FAILURE");
            }
        }
        int archived = 0;
        if (archiveStale) {
            try {
                long generation = writes.generation();
                var stale = repository.staleBatch().stream().filter(memory -> decay.effectiveImportance(memory, clock.instant()) < settings.archiveThreshold()).toList();
                archived = writes.commit(generation, () -> {
                    int count = 0;
                    for (var memory : stale) {
                        // Usage is independent of generation. Lock and recheck it at the actual write.
                        var current = repository.lock(memory.id());
                        var lastUsed = current.lastAccessedAt() == null ? current.createdAt() : current.lastAccessedAt();
                        if (!current.active() || !lastUsed.isBefore(clock.instant().minus(java.time.Duration.ofDays(settings.archiveAfterDays())))
                                || decay.effectiveImportance(current, clock.instant()) >= settings.archiveThreshold()) continue;
                        var draft = new MemoryDraft(current.type(), current.content(), current.importance(), current.confidence(),
                                current.tags(), current.metadata(), current.source(), current.sourceConversationId(), null);
                        repository.replace(current, draft, null, true);
                        count++;
                    }
                    return count;
                });
            } catch (MemoryException exception) { errors.add(exception.kind().name()); }
            catch (DataAccessException exception) { errors.add("DATABASE_FAILURE"); }
        }
        log.info("event=consolidation_complete inspected={} summaries={} relations={} archived={} errors={}",
                visited.size(), summaries.size(), relationCount, archived, errors.size());
        return new Result(errors.isEmpty(), visited.size(), List.copyOf(summaries), relationCount, archived, List.copyOf(errors));
    }

    public static void validatePlan(Plan plan, List<Memory> sources) {
        validatePlan(plan, sources, List.of());
    }

    public static void validatePlan(Plan plan, List<Memory> sources, List<MemoryRepository.Contradiction> knownContradictions) {
        var byId = new HashMap<UUID, Memory>(); sources.forEach(memory -> byId.put(memory.id(), memory));
        var summaryTexts = new HashSet<String>();
        for (var summary : plan.summaries()) {
            var ids = new HashSet<>(summary.sourceIds());
            if (ids.size() < 2 || ids.size() != summary.sourceIds().size() || !byId.keySet().containsAll(ids)
                    || !summaryTexts.add(TextNormalizer.normalize(summary.content()))
                    || sources.stream().anyMatch(source -> TextNormalizer.normalize(summary.content()).equals(source.normalizedContent())))
                throw MemoryException.malformed();
        }
        var retired = new HashSet<UUID>();
        var survivors = new HashSet<UUID>();
        for (var relation : plan.relations()) {
            if (!byId.containsKey(relation.olderId()) || !byId.containsKey(relation.newerId()) || relation.olderId().equals(relation.newerId()))
                throw MemoryException.malformed();
            if (Set.of(MemoryRelation.DUPLICATE, MemoryRelation.UPDATE, MemoryRelation.REFINEMENT).contains(relation.relation())) {
                var older = byId.get(relation.olderId()); var newer = byId.get(relation.newerId());
                if (older.type() != newer.type() || newer.createdAt().isBefore(older.createdAt())
                        || !retired.add(older.id()) || survivors.contains(older.id()) || retired.contains(newer.id()))
                    throw MemoryException.malformed();
                survivors.add(newer.id());
            }
        }
        if (plan.summaries().stream().anyMatch(summary -> summary.sourceIds().stream().anyMatch(retired::contains)))
            throw MemoryException.malformed();
        // Never summarize both sides of a known contradiction into a single asserted truth.
        for (var contradiction : knownContradictions) {
            if (plan.summaries().stream().anyMatch(summary -> summary.sourceIds().contains(contradiction.fromId())
                    && summary.sourceIds().contains(contradiction.toId())))
                throw MemoryException.malformed();
        }
        for (var relation : plan.relations()) {
            if (relation.relation() == MemoryRelation.CONTRADICTION && plan.summaries().stream()
                    .anyMatch(summary -> summary.sourceIds().contains(relation.olderId()) && summary.sourceIds().contains(relation.newerId())))
                throw MemoryException.malformed();
        }
    }

    private Applied apply(Plan plan, List<Prepared> prepared, List<Memory> cluster) {
        UUID group = UUID.randomUUID();
        var ids = new ArrayList<UUID>();
        for (var relation : plan.relations()) {
            switch (relation.relation()) {
                case DUPLICATE, UPDATE, REFINEMENT -> {
                    var older = cluster.stream().filter(memory -> memory.id().equals(relation.olderId())).findFirst().orElseThrow();
                    repository.supersede(older, "CONSOLIDATION_SUPERSEDED");
                    repository.link(relation.newerId(), relation.olderId(), "SUPERSEDES");
                    if (relation.relation() == MemoryRelation.REFINEMENT) repository.link(relation.newerId(), relation.olderId(), "REFINES");
                }
                case CONTRADICTION -> repository.link(relation.newerId(), relation.olderId(), "CONTRADICTS");
                case COMPATIBLE, UNRELATED -> { }
            }
        }
        // A relation can also invalidate a source indirectly through another summary.
        var required = new HashSet<UUID>();
        for (var item : prepared) {
            required.addAll(item.summary().sourceIds());
            if (item.existing() != null) required.add(item.existing().id());
        }
        repository.requireActive(required);
        for (var item : prepared) {
            var memory = item.existing() == null
                    ? repository.insert(item.draft(), item.embedding(), null, item.summary().sourceIds().getFirst(), group) : item.existing();
            for (var sourceId : item.summary().sourceIds()) repository.link(memory.id(), sourceId, "CONSOLIDATED_FROM");
            ids.add(memory.id());
        }
        var processed = new HashSet<>(ids); cluster.forEach(memory -> processed.add(memory.id()));
        repository.markConsolidated(processed);
        return new Applied(ids, plan.relations().size());
    }
}
