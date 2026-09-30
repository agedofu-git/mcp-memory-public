package dev.memory.service;

import dev.memory.controller.Requests;
import dev.memory.domain.*;
import dev.memory.embedding.EmbeddingClient;
import dev.memory.repository.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private final MemoryRepository repository;
    private final MemoryWrites writes;
    private final EmbeddingClient embeddings;
    public MemoryService(MemoryRepository repository, MemoryWrites writes, EmbeddingClient embeddings) {
        this.repository = repository; this.writes = writes; this.embeddings = embeddings;
    }
    public Memory create(Requests.Create request) {
        validateMetadata(request.metadata());
        var draft = new MemoryDraft(request.type(), request.content(), request.importance() == null ? 0.7 : request.importance(),
                request.confidence() == null ? 0.99 : request.confidence(), request.tags(), request.metadata(), "MANUAL", request.sourceConversationId(), null);
        long generation = writes.generation();
        var exact = repository.exact(draft.type(), draft.content(), (String) draft.metadata().get("project"));
        if (exact.isPresent()) return writes.commit(generation, () -> {
            repository.evidence(exact.get().id(), draft); return exact.get();
        });
        var embedding = embeddings.embed(draft.content());
        return writes.commit(generation, () -> repository.insert(draft, embedding, null, null, null));
    }
    public Memory get(UUID id) { return repository.require(id); }
    public List<Memory> list(int limit, int offset, boolean historical) { return repository.list(limit, offset, historical); }
    public Memory patch(UUID id, Requests.Patch patch) {
        if (patch.content() != null && patch.content().isBlank())
            throw new MemoryException(MemoryException.Kind.INVALID_REQUEST, "Content must not be blank.");
        validateMetadata(patch.metadata());
        long generation = writes.generation();
        var before = get(id);
        if (before.version() != patch.version()) throw MemoryException.conflict();
        var draft = new MemoryDraft(patch.type() == null ? before.type() : patch.type(),
                patch.content() == null ? before.content() : patch.content(),
                patch.importance() == null ? before.importance() : patch.importance(),
                patch.confidence() == null ? before.confidence() : patch.confidence(),
                patch.tags() == null ? before.tags() : patch.tags(), patch.metadata() == null ? before.metadata() : patch.metadata(),
                before.source(), before.sourceConversationId(), null);
        var embedding = before.normalizedContent().equals(TextNormalizer.normalize(draft.content())) ? null : embeddings.embed(draft.content());
        boolean archived = patch.archived() == null ? before.archived() : patch.archived();
        return writes.commit(generation, () -> repository.replace(before, draft, embedding, archived));
    }
    public Memory archive(UUID id, Requests.Archive request) {
        var result = patch(id, new Requests.Patch(request.version(), null, null, null, null, null, null, request.archived()));
        log.info("event=archive_changed memoryId={} archived={}", id, result.archived());
        return result;
    }
    public void delete(UUID id) {
        long generation = writes.generation();
        writes.commit(generation, () -> { repository.delete(id); return null; });
    }
    public Map<String, Object> history(UUID id) { return repository.history(id); }
    public int recordUsage(List<UUID> ids) {
        // PostgreSQL increments statistics atomically; usage does not change semantic decisions.
        return repository.recordUsage(ids);
    }

    private static void validateMetadata(Map<String, Object> metadata) {
        if (metadata != null && (metadata.values().stream().anyMatch(Objects::isNull)
                || (metadata.containsKey("project") && (!(metadata.get("project") instanceof String project) || project.length() > 100))))
            throw new MemoryException(MemoryException.Kind.INVALID_REQUEST, "Metadata values must be non-null; project must be a string of at most 100 characters.");
    }
}
