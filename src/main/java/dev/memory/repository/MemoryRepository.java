package dev.memory.repository;

import dev.memory.config.MemoryProperties;
import dev.memory.controller.Requests.Search;
import dev.memory.domain.*;
import dev.memory.embedding.EmbeddingClient.Embedding;
import dev.memory.service.MemoryException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class MemoryRepository {
    public record Hit(Memory memory, double semantic, double text) {}
    public record Contradiction(UUID fromId, UUID toId) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final MemoryProperties properties;
    private final RowMapper<Memory> mapper;

    public MemoryRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json, MemoryProperties properties) {
        this.jdbc = jdbc; this.json = json; this.properties = properties; this.mapper = (rs, row) -> map(rs);
    }

    public Optional<Memory> find(UUID id) {
        return jdbc.query("SELECT * FROM memories WHERE id=:id", Map.of("id", id), mapper).stream().findFirst();
    }
    public Memory require(UUID id) {
        return find(id).orElseThrow(() -> new MemoryException(MemoryException.Kind.NOT_FOUND, "Memory not found."));
    }
    /** Used inside a write transaction to recheck statistics before automatic archiving. */
    public Memory lock(UUID id) {
        return jdbc.query("SELECT * FROM memories WHERE id=:id FOR UPDATE", Map.of("id", id), mapper)
                .stream().findFirst().orElseThrow(() -> new MemoryException(MemoryException.Kind.NOT_FOUND, "Memory not found."));
    }
    public void requireActive(Collection<UUID> ids) {
        var distinct = new HashSet<>(ids);
        if (distinct.isEmpty()) return;
        Integer count = jdbc.queryForObject("SELECT count(*) FROM memories WHERE id IN (:ids) AND active",
                Map.of("ids", distinct), Integer.class);
        if (count == null || count != distinct.size()) throw MemoryException.conflict();
    }
    public List<Memory> list(int limit, int offset, boolean historical) {
        return jdbc.query("SELECT * FROM memories WHERE (:history OR (active AND NOT archived AND NOT superseded)) ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset",
                Map.of("history", historical, "limit", limit, "offset", offset), mapper);
    }
    public Optional<Memory> exact(MemoryType type, String content, String project) {
        return jdbc.query("""
                SELECT * FROM memories WHERE type=:type AND md5(normalized_content)=md5(:normalized)
                    AND normalized_content=:normalized AND coalesce(metadata->>'project','')=:project
                    AND active AND NOT archived AND NOT superseded
                """, Map.of("type", type.name(), "normalized", TextNormalizer.normalize(content), "project", project == null ? "" : project), mapper).stream().findFirst();
    }

    public Memory insert(MemoryDraft draft, Embedding embedding, UUID supersedes, UUID parent, UUID group) {
        UUID id = UUID.randomUUID();
        var params = draftParams(draft).addValue("id", id).addValue("vector", embedding.vectorLiteral())
                .addValue("provider", embedding.provider()).addValue("model", embedding.model())
                .addValue("dimensions", embedding.dimensions()).addValue("embeddingVersion", embedding.version())
                .addValue("supersedes", supersedes).addValue("parent", parent).addValue("group", group);
        jdbc.update("""
                INSERT INTO memories(id,type,content,normalized_content,embedding,embedding_provider,embedding_model,
                    embedding_dimensions,embedding_version,source,source_conversation_id,importance,confidence,tags,metadata,
                    supersedes_memory_id,parent_memory_id,consolidation_group_id)
                VALUES (:id,:type,:content,:normalized,CAST(:vector AS vector),:provider,:model,:dimensions,:embeddingVersion,
                    :source,:conversation,:importance,:confidence,CAST(:tags AS jsonb),CAST(:metadata AS jsonb),:supersedes,:parent,:group)
                """, params);
        evidence(id, draft);
        revision(id, "CREATED");
        return require(id);
    }

    public Memory replace(Memory before, MemoryDraft draft, Embedding embedding, boolean archived) {
        var params = draftParams(draft).addValue("id", before.id()).addValue("version", before.version())
                .addValue("archived", archived).addValue("active", !archived && !before.superseded() && !before.stale());
        String vectorUpdate = "";
        if (embedding != null) {
            vectorUpdate = """
                    , embedding=CAST(:vector AS vector), embedding_provider=:provider, embedding_model=:model,
                    embedding_dimensions=:dimensions, embedding_version=:embeddingVersion
                    """;
            params.addValue("vector", embedding.vectorLiteral()).addValue("provider", embedding.provider())
                    .addValue("model", embedding.model()).addValue("dimensions", embedding.dimensions()).addValue("embeddingVersion", embedding.version());
        }
        int changed = jdbc.update("""
                UPDATE memories SET type=:type, content=:content, normalized_content=:normalized,
                    importance=:importance, confidence=:confidence, tags=CAST(:tags AS jsonb), metadata=CAST(:metadata AS jsonb),
                    archived=:archived, active=:active, updated_at=now(), version=version+1
                """ + vectorUpdate + " WHERE id=:id AND version=:version", params);
        if (changed != 1) throw MemoryException.conflict();
        revision(before.id(), "EDITED");
        invalidateDerived(before.id());
        return require(before.id());
    }

    public void supersede(Memory before, String reason) {
        int changed = jdbc.update("""
                UPDATE memories SET active=false, superseded=true, updated_at=now(), version=version+1
                WHERE id=:id AND version=:version AND active AND NOT archived AND NOT superseded
                """, Map.of("id", before.id(), "version", before.version()));
        if (changed != 1) throw MemoryException.conflict();
        revision(before.id(), reason);
        invalidateDerived(before.id());
    }
    public void evidence(UUID id, MemoryDraft draft) {
        jdbc.update("""
                INSERT INTO memory_evidence(memory_id,conversation_id,source,evidence)
                VALUES (:id,:conversation,:source,:evidence) ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource("id", id).addValue("conversation", draft.conversationId())
                .addValue("source", draft.source()).addValue("evidence", draft.evidence()));
    }
    public void link(UUID from, UUID to, String relation) {
        jdbc.update("INSERT INTO memory_links(from_id,to_id,relation,target_version) SELECT :from,id,:relation,version FROM memories WHERE id=:to ON CONFLICT DO NOTHING",
                Map.of("from", from, "to", to, "relation", relation));
    }
    public void revision(UUID id, String reason) {
        jdbc.update("""
                INSERT INTO memory_revisions(memory_id,reason,snapshot)
                SELECT id,:reason,to_jsonb(m)-'embedding'-'search_document' FROM memories m WHERE id=:id
                """, Map.of("id", id, "reason", reason));
    }
    public void delete(UUID id) {
        // Invalidate before ON DELETE CASCADE removes the provenance edges.
        invalidateDerived(id);
        if (jdbc.update("DELETE FROM memories WHERE id=:id", Map.of("id", id)) == 0)
            throw new MemoryException(MemoryException.Kind.NOT_FOUND, "Memory not found.");
    }
    private void invalidateDerived(UUID sourceId) {
        // UNION also terminates safely if historical data contains a provenance cycle.
        var invalidated = jdbc.query("""
                WITH RECURSIVE derived(id) AS (
                    SELECT from_id FROM memory_links WHERE to_id=:id AND relation='CONSOLIDATED_FROM'
                    UNION
                    SELECT l.from_id FROM memory_links l JOIN derived d ON l.to_id=d.id
                    WHERE l.relation='CONSOLIDATED_FROM'
                )
                UPDATE memories SET stale=true, active=false, version=version+1, updated_at=now()
                WHERE id IN (SELECT id FROM derived) AND id<>:id AND NOT stale
                RETURNING id
                """, Map.of("id", sourceId), (rs, row) -> rs.getObject("id", UUID.class));
        if (invalidated.isEmpty()) return;
        var params = Map.of("ids", invalidated);
        jdbc.update("""
                INSERT INTO memory_revisions(memory_id,reason,snapshot)
                SELECT id,'SOURCE_CHANGED',to_jsonb(m)-'embedding'-'search_document'
                FROM memories m WHERE id IN (:ids)
                """, params);
        // All surviving source rows can participate in a fresh consolidation immediately.
        jdbc.update("""
                UPDATE memories SET last_consolidated_at=NULL WHERE active AND id IN (
                    SELECT to_id FROM memory_links WHERE from_id IN (:ids) AND relation='CONSOLIDATED_FROM'
                )
                """, params);
    }
    public int recordUsage(List<UUID> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.update("""
                UPDATE memories SET access_count=access_count+1,
                    last_accessed_at=greatest(last_accessed_at, clock_timestamp())
                WHERE id IN (:ids) AND active AND NOT archived AND NOT superseded
                """, Map.of("ids", new HashSet<>(ids)));
    }
    public Map<String, Object> history(UUID id) {
        require(id);
        return Map.of("revisions", jsonRows("SELECT to_jsonb(r)::text AS data FROM memory_revisions r WHERE memory_id=:id ORDER BY id", id),
                "links", jsonRows("SELECT to_jsonb(l)::text AS data FROM memory_links l WHERE from_id=:id OR to_id=:id ORDER BY created_at", id),
                "evidence", jsonRows("SELECT to_jsonb(e)::text AS data FROM memory_evidence e WHERE memory_id=:id ORDER BY id", id));
    }
    private List<Map<String, Object>> jsonRows(String sql, UUID id) {
        return jdbc.query(sql, Map.of("id", id), (rs, row) -> json.readValue(rs.getString("data"), new TypeReference<Map<String, Object>>() {}));
    }

    public List<Contradiction> contradictions(Collection<UUID> ids) {
        if (ids.size() < 2) return List.of();
        return jdbc.query("""
                SELECT from_id, to_id FROM memory_links
                WHERE relation='CONTRADICTS' AND from_id IN (:ids) AND to_id IN (:ids)
                ORDER BY from_id, to_id
                """, Map.of("ids", ids), (rs, row) -> new Contradiction(
                rs.getObject("from_id", UUID.class), rs.getObject("to_id", UUID.class)));
    }

    public List<Hit> search(Search search, Embedding embedding, int pool) {
        var params = modelParams(embedding).addValue("vector", embedding.vectorLiteral()).addValue("query", search.query())
                .addValue("pool", pool).addValue("tags", json.writeValueAsString(search.tags() == null ? List.of() : search.tags()));
        var filters = new StringBuilder("embedding_provider=:provider AND embedding_model=:model AND embedding_dimensions=:dimensions AND embedding_version=:embeddingVersion");
        if (search.effectiveActiveOnly()) filters.append(" AND active");
        if (!search.includeArchived()) filters.append(" AND NOT archived");
        if (!search.includeSuperseded()) filters.append(" AND NOT superseded");
        if (search.type() != null) { filters.append(" AND type=:type"); params.addValue("type", search.type().name()); }
        filters.append(" AND tags @> CAST(:tags AS jsonb)");
        if (search.project() != null) { filters.append(" AND metadata->>'project'=:project"); params.addValue("project", search.project()); }
        if (search.minimumImportance() != null) { filters.append(" AND importance>=:minimum"); params.addValue("minimum", search.minimumImportance()); }
        if (search.createdFrom() != null) { filters.append(" AND created_at>=:dateFrom"); params.addValue("dateFrom", java.sql.Timestamp.from(search.createdFrom())); }
        if (search.createdTo() != null) { filters.append(" AND created_at<=:dateTo"); params.addValue("dateTo", java.sql.Timestamp.from(search.createdTo())); }
        // Separate index-friendly candidate branches; never materialize all memories in Java.
        String sql = """
                WITH semantic_ids AS (
                    SELECT id FROM memories WHERE %s ORDER BY embedding <=> CAST(:vector AS vector) LIMIT :pool
                ), text_ids AS (
                    SELECT id FROM memories WHERE %s AND search_document @@ plainto_tsquery('simple', :query)
                    ORDER BY ts_rank_cd(search_document, plainto_tsquery('simple', :query), 32) DESC LIMIT :pool
                ), candidates AS (SELECT id FROM semantic_ids UNION SELECT id FROM text_ids)
                SELECT m.*, greatest(0, 1-(embedding <=> CAST(:vector AS vector))) AS semantic_score,
                    ts_rank_cd(search_document, plainto_tsquery('simple', :query), 32) AS text_score
                FROM memories m JOIN candidates c ON c.id=m.id
                """.formatted(filters, filters);
        return jdbc.query(sql, params, (rs, row) -> new Hit(map(rs), rs.getDouble("semantic_score"), rs.getDouble("text_score")));
    }

    public List<Hit> related(MemoryDraft draft, Embedding embedding) {
        var search = new Search(draft.content(), properties.duplicate().candidateLimit(), draft.type(), null,
                true, false, false, null, (String) draft.metadata().get("project"), null, null, null, false);
        return search(search, embedding, properties.duplicate().candidateLimit()).stream()
                .filter(hit -> Objects.equals(hit.memory().metadata().get("project"), draft.metadata().get("project")))
                .filter(hit -> hit.semantic() >= properties.duplicate().relationThreshold() || hit.text() > 0)
                .sorted(Comparator.comparingDouble(Hit::semantic).reversed())
                .limit(properties.duplicate().candidateLimit()).toList();
    }

    public List<Memory> consolidationBatch() {
        return jdbc.query("""
                SELECT * FROM memories WHERE active AND NOT archived AND NOT superseded
                    AND (last_consolidated_at IS NULL OR updated_at > last_consolidated_at)
                    AND (last_consolidated_at IS NULL OR last_consolidated_at < now() - make_interval(days => :cooldown))
                ORDER BY last_consolidated_at NULLS FIRST, created_at, id LIMIT :batch
                """, Map.of("cooldown", properties.consolidation().cooldownDays(), "batch", properties.consolidation().batchSize()), mapper);
    }
    public List<Memory> cluster(Memory seed) {
        return jdbc.query("""
                SELECT m.* FROM memories m JOIN memories seed ON seed.id=:id
                WHERE m.id<>seed.id AND m.active AND NOT m.archived AND NOT m.superseded
                    AND m.embedding_model=seed.embedding_model AND m.embedding_provider=seed.embedding_provider
                    AND m.embedding_version=seed.embedding_version
                    AND (m.metadata->>'project') IS NOT DISTINCT FROM (seed.metadata->>'project')
                    AND (m.last_consolidated_at IS NULL OR m.last_consolidated_at < now() - make_interval(days => :cooldown))
                    AND (1-(m.embedding <=> seed.embedding)) >= :threshold
                ORDER BY m.embedding <=> seed.embedding LIMIT :limit
                """, Map.of("id", seed.id(), "cooldown", properties.consolidation().cooldownDays(),
                "threshold", properties.consolidation().similarityThreshold(), "limit", properties.consolidation().clusterSize() - 1), mapper);
    }
    public void markConsolidated(Collection<UUID> ids) {
        if (!ids.isEmpty()) jdbc.update("UPDATE memories SET last_consolidated_at=now() WHERE id IN (:ids)", Map.of("ids", ids));
    }
    public List<Memory> staleBatch() {
        return jdbc.query("""
                SELECT * FROM memories WHERE active AND NOT archived AND NOT superseded
                    AND source<>'MANUAL' AND coalesce(last_accessed_at,created_at)<now()-make_interval(days => :days)
                ORDER BY coalesce(last_accessed_at,created_at) LIMIT :batch
                """, Map.of("days", properties.consolidation().archiveAfterDays(), "batch", properties.consolidation().batchSize()), mapper);
    }

    private MapSqlParameterSource modelParams(Embedding embedding) {
        return new MapSqlParameterSource("provider", embedding.provider()).addValue("model", embedding.model())
                .addValue("dimensions", embedding.dimensions()).addValue("embeddingVersion", embedding.version());
    }
    private MapSqlParameterSource draftParams(MemoryDraft draft) {
        return new MapSqlParameterSource("type", draft.type().name()).addValue("content", draft.content())
                .addValue("normalized", TextNormalizer.normalize(draft.content())).addValue("source", draft.source())
                .addValue("conversation", draft.conversationId()).addValue("importance", draft.importance())
                .addValue("confidence", draft.confidence()).addValue("tags", json.writeValueAsString(draft.tags()))
                .addValue("metadata", json.writeValueAsString(draft.metadata()));
    }
    private Memory map(ResultSet rs) throws SQLException {
        return new Memory(rs.getObject("id", UUID.class), MemoryType.valueOf(rs.getString("type")), rs.getString("content"),
                rs.getString("normalized_content"), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "last_accessed_at"),
                rs.getString("source"), rs.getString("source_conversation_id"), rs.getDouble("importance"), rs.getDouble("confidence"),
                rs.getLong("access_count"), json.readValue(rs.getString("tags"), new TypeReference<List<String>>() {}),
                json.readValue(rs.getString("metadata"), new TypeReference<Map<String, Object>>() {}),
                rs.getBoolean("active"), rs.getBoolean("archived"), rs.getBoolean("superseded"), rs.getBoolean("stale"),
                rs.getObject("supersedes_memory_id", UUID.class), rs.getObject("parent_memory_id", UUID.class),
                rs.getObject("consolidation_group_id", UUID.class), rs.getString("embedding_provider"), rs.getString("embedding_model"),
                rs.getInt("embedding_dimensions"), rs.getString("embedding_version"), rs.getLong("version"));
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var timestamp = rs.getTimestamp(name); return timestamp == null ? null : timestamp.toInstant();
    }
}
