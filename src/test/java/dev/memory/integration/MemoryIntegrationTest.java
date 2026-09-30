package dev.memory.integration;

import dev.memory.MemoryApplication;
import dev.memory.config.MemoryProperties;
import dev.memory.controller.Requests;
import dev.memory.domain.*;
import dev.memory.embedding.EmbeddingClient;
import dev.memory.ingestion.MemoryIngestionService;
import dev.memory.ingestion.MemoryAdmission;
import dev.memory.llm.LlmClient;
import dev.memory.repository.MemoryWrites;
import dev.memory.repository.MemoryRepository;
import dev.memory.service.*;
import dev.memory.consolidation.MemoryConsolidationService;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@SpringBootTest(classes = {MemoryApplication.class, MemoryIntegrationTest.Providers.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemoryIntegrationTest {
    private static PostgreSQLContainer container;
    private static String baseUrl;
    private static String user;
    private static String password;
    private static String schema;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        baseUrl = System.getenv("TEST_DB_URL");
        user = System.getenv().getOrDefault("TEST_DB_USER", "memory");
        password = System.getenv().getOrDefault("TEST_DB_PASSWORD", "");
        if (baseUrl == null || baseUrl.isBlank()) {
            container = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18").asCompatibleSubstituteFor("postgres"));
            container.start();
            baseUrl = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        }
        String testSchema = "memory_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(baseUrl, user, password); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
            statement.execute("CREATE SCHEMA " + testSchema);
            schema = testSchema;
        }
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.placeholders.embeddingDimensions", () -> 3);
        registry.add("memory.embedding.dimensions", () -> 3);
        registry.add("memory.embedding.model", () -> "test-model");
        registry.add("memory.embedding.provider", () -> "test");
        registry.add("memory.embedding.version", () -> "1");
        registry.add("memory.consolidation.schedule-enabled", () -> false);
    }

    @TestConfiguration
    static class Providers {
        @Bean @Primary FakeEmbedding fakeEmbedding(MemoryProperties properties) { return new FakeEmbedding(properties); }
        @Bean @Primary FakeLlm fakeLlm() { return new FakeLlm(); }
    }
    static class FakeEmbedding implements EmbeddingClient {
        final AtomicInteger calls = new AtomicInteger();
        private final MemoryProperties properties;
        boolean fail;
        FakeEmbedding(MemoryProperties properties) { this.properties = properties; }
        public Embedding embed(String content) {
            calls.incrementAndGet();
            if (fail) throw new MemoryException(MemoryException.Kind.PROVIDER_UNAVAILABLE, "Test embedding failure.");
            String lower = content.toLowerCase(Locale.ROOT);
            float[] vector = lower.contains("server") || lower.contains("ram") ? new float[]{1, 0.05f, 0.02f}
                    : lower.contains("java") ? new float[]{0.05f, 1, 0.02f} : new float[]{0.02f, 0.05f, 1};
            var config = properties.embedding();
            return new Embedding(vector, config.provider(), config.model(), config.dimensions(), config.version());
        }
    }
    static class FakeLlm implements LlmClient {
        final Queue<String> replies = new ConcurrentLinkedQueue<>();
        java.util.function.Function<String, String> responder;
        public String complete(String prompt, String input) {
            if (responder != null) return responder.apply(input);
            String reply = replies.poll();
            if (reply == null) throw new AssertionError("Unexpected LLM call in integration test");
            return reply;
        }
    }

    @Autowired MemoryService service;
    @Autowired MemorySearchService search;
    @Autowired MemoryIngestionService ingestion;
    @Autowired MemoryConsolidationService consolidation;
    @Autowired MemoryWrites writes;
    @Autowired MemoryRepository repository;
    @Autowired MemoryAdmission admission;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeEmbedding embeddings;
    @Autowired FakeLlm llm;
    @Autowired JsonMapper json;
    @LocalServerPort int port;

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE memories CASCADE");
        jdbc.update("UPDATE memory_state SET generation=0 WHERE id=1");
        embeddings.calls.set(0); embeddings.fail = false; llm.replies.clear();
        llm.responder = null;
    }
    @AfterAll static void cleanup() throws Exception {
        if (schema != null) {
            try (var connection = DriverManager.getConnection(baseUrl, user, password); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
        if (container != null) container.stop();
    }
    private Memory create(MemoryType type, String content) {
        return service.create(new Requests.Create(type, content, 0.7, 0.95, List.of("technical"), Map.of("project", "memory"), null));
    }
    private Requests.Search query(String text, boolean archived, boolean superseded) {
        return new Requests.Search(text, 10, null, null, null, archived, superseded, null, null, null, null, null, true);
    }
    private String extraction(String content, String evidence) {
        return json.writeValueAsString(Map.of("memories", List.of(Map.of("type", "FACT", "content", content, "importance", 0.7,
                "confidence", 0.95, "tags", List.of("server"), "evidence", evidence))));
    }
    private void candidate(String content, String evidence) {
        llm.replies.add(extraction(content, evidence));
        llm.replies.add("{\"keep\":true,\"reason\":\"durable explicit hardware fact\"}");
    }

    @Test void manualCrudHistoryAndExactDuplicateReuseEmbeddings() {
        var created = create(MemoryType.FACT, "Server has 16 GB RAM");
        assertThat(service.get(created.id()).content()).isEqualTo(created.content());
        assertThat(create(MemoryType.FACT, "Server has 16 GB RAM").id()).isEqualTo(created.id());
        assertThat(embeddings.calls).hasValue(1);
        var edited = service.patch(created.id(), new Requests.Patch(created.version(), null, "Server has 32 GB RAM", null, null, null, null, null));
        assertThat(edited.version()).isEqualTo(1);
        assertThat(embeddings.calls).hasValue(2);
        assertThat((List<?>) service.history(created.id()).get("revisions")).hasSize(2);
        assertThatThrownBy(() -> service.patch(created.id(), new Requests.Patch(0L, null, "stale write", null, null, null, null, null)))
                .isInstanceOf(MemoryException.class);
        service.delete(created.id());
        assertThatThrownBy(() -> service.get(created.id())).isInstanceOf(MemoryException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_revisions", Long.class)).isZero();
    }
    @Test void semanticRetrievalFiltersArchivesAndTracksOnlyExplicitUsage() {
        var server = create(MemoryType.FACT, "Server has 32 GB RAM");
        create(MemoryType.PREFERENCE, "User prefers Java");
        var results = search.search(query("How much RAM does the server have?", false, false));
        assertThat(results.getFirst().memory().id()).isEqualTo(server.id());
        assertThat(results.getFirst().components().semantic()).isGreaterThan(0.99);
        assertThat(service.get(server.id()).accessCount()).isZero();
        assertThat(service.recordUsage(List.of(server.id(), server.id()))).isEqualTo(1);
        assertThat(service.get(server.id()).accessCount()).isEqualTo(1);
        var archived = service.archive(server.id(), new Requests.Archive(server.version(), true));
        assertThat(search.search(query("server RAM", false, false))).noneMatch(result -> result.memory().id().equals(server.id()));
        assertThat(search.search(query("server RAM", true, false))).anyMatch(result -> result.memory().id().equals(server.id()));
        assertThat(service.recordUsage(List.of(server.id()))).isZero();
        assertThat(service.archive(server.id(), new Requests.Archive(archived.version(), false)).active()).isTrue();
    }
    @Test void upgradeSupersedesWithProvenanceAndRetryDoesNotAccumulateDuplicates() {
        candidate("Server has 16 GB RAM", "16 GB RAM");
        var first = ingestion.ingest(new Requests.Ingest("day-1", "My server has 16 GB RAM", null, "memory", true));
        UUID oldId = first.outcomes().getFirst().memoryId();
        candidate("Server has 32 GB RAM", "upgraded my server to 32 GB RAM");
        llm.replies.add("{\"relation\":\"UPDATE\",\"reason\":\"explicit upgrade\"}");
        var second = ingestion.ingest(new Requests.Ingest("day-7", "I upgraded my server to 32 GB RAM", null, "memory", true));
        assertThat(second.complete()).isTrue();
        assertThat(second.outcomes().getFirst().action()).isEqualTo("SUPERSEDE");
        UUID newId = second.outcomes().getFirst().memoryId();
        assertThat(service.get(oldId).superseded()).isTrue();
        assertThat(service.get(newId).supersedesMemoryId()).isEqualTo(oldId);
        assertThat(search.search(query("server RAM", false, false))).noneMatch(result -> result.memory().id().equals(oldId));
        assertThat(search.search(query("server RAM", false, true))).anyMatch(result -> result.memory().id().equals(oldId));
        candidate("Server has 32 GB RAM", "upgraded my server to 32 GB RAM");
        var retried = ingestion.ingest(new Requests.Ingest("day-7", "I upgraded my server to 32 GB RAM", null, "memory", true));
        assertThat(retried.outcomes().getFirst().action()).isEqualTo("IGNORE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memories", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_evidence WHERE memory_id=?", Long.class, newId)).isEqualTo(1);
        assertThat((List<?>) service.history(newId).get("links")).hasSize(1);
    }
    @Test void unresolvedContradictionsPreserveBothClaims() {
        create(MemoryType.FACT, "Server has 16 GB RAM");
        candidate("Server has 32 GB RAM", "server has 32 GB RAM");
        llm.replies.add("{\"relation\":\"CONTRADICTION\",\"reason\":\"no evidence of an upgrade\"}");
        var result = ingestion.ingest(new Requests.Ingest("day-2", "The server has 32 GB RAM", null, "memory", true));
        assertThat(result.outcomes().getFirst().action()).isEqualTo("CONTRADICTION");
        assertThat(service.list(20, 0, false)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_links WHERE relation='CONTRADICTS'", Long.class)).isEqualTo(1);
    }
    @Test void failedEmbeddingDoesNotChangeOldTruthAndStaleCatalogCannotCommit() {
        var old = create(MemoryType.FACT, "Server has 16 GB RAM");
        embeddings.fail = true;
        assertThatThrownBy(() -> service.patch(old.id(), new Requests.Patch(old.version(), null, "Server has 32 GB RAM", null, null, null, null, null)))
                .isInstanceOf(MemoryException.class);
        assertThat(service.get(old.id()).content()).isEqualTo("Server has 16 GB RAM");
        embeddings.fail = false;
        long generation = writes.generation();
        create(MemoryType.PREFERENCE, "User prefers Java");
        assertThatThrownBy(() -> writes.commit(generation, () -> null)).isInstanceOf(MemoryException.class);
    }
    @Test void consolidationLinksSourcesAndPreservesThem() {
        var a = create(MemoryType.PROJECT, "Added pgvector to the memory project");
        var b = create(MemoryType.PROJECT, "Implemented semantic memory search");
        llm.replies.add(json.writeValueAsString(Map.of("summaries", List.of(Map.of("type", "PROJECT", "content", "Building a memory system with pgvector and semantic search",
                "importance", 0.8, "confidence", 0.99, "tags", List.of("memory"), "sourceIds", List.of(a.id(), b.id()))), "relations", List.of())));
        var result = consolidation.consolidate(false);
        assertThat(result.complete()).isTrue();
        assertThat(result.summaries()).hasSize(1);
        var summary = service.get(result.summaries().getFirst());
        assertThat(summary.source()).isEqualTo("CONSOLIDATED");
        assertThat(summary.confidence()).isEqualTo(0.95);
        assertThat(summary.importance()).isEqualTo(0.7);
        assertThat(service.get(a.id()).active()).isTrue();
        assertThat((List<?>) service.history(summary.id()).get("links")).hasSize(2);
        assertThat(consolidation.consolidate(false).summaries()).isEmpty();
    }
    @Test void postgresIndexesAndModelIsolationAreReal() {
        var memory = create(MemoryType.FACT, "Server has 32 GB RAM");
        assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname=?", String.class, schema))
                .anyMatch(index -> index.contains("USING hnsw"));
        jdbc.update("UPDATE memories SET embedding_model='old-incompatible-model' WHERE id=?", memory.id());
        assertThat(search.search(query("server RAM", false, false))).isEmpty();
    }
    @Test void consolidationCannotForgetStoredContradictions() {
        var a = create(MemoryType.FACT, "Server has 16 GB RAM");
        var b = create(MemoryType.FACT, "Server has 32 GB RAM");
        jdbc.update("INSERT INTO memory_links(from_id,to_id,relation,target_version) VALUES (?,?,'CONTRADICTS',?)",
                b.id(), a.id(), a.version());
        llm.replies.add(json.writeValueAsString(Map.of("summaries", List.of(Map.of("type", "FACT",
                "content", "Server consistently has 32 GB RAM", "importance", 0.7, "confidence", 0.95,
                "tags", List.of("server"), "sourceIds", List.of(a.id(), b.id()))), "relations", List.of())));
        var result = consolidation.consolidate(false);
        assertThat(result.complete()).isFalse();
        assertThat(result.errors()).containsExactly("INVALID_PROVIDER_OUTPUT");
        assertThat(result.summaries()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memories", Long.class)).isEqualTo(2);
        assertThat(service.get(a.id()).active()).isTrue();
        assertThat(service.get(b.id()).active()).isTrue();
    }
    @Test void restHealthValidationPayloadLimitAndCrud() throws Exception {
        assertThat(http("GET", "/api/health", null).statusCode()).isEqualTo(200);
        assertThat(http("POST", "/api/memories", "{\"type\":\"INVALID\",\"content\":\"x\"}").statusCode()).isEqualTo(400);
        assertThat(http("POST", "/api/memories/search", "{\"query\":\"x\",\"limit\":0}").statusCode()).isEqualTo(400);
        assertThat(http("POST", "/api/memories/search", "x".repeat(262145)).statusCode()).isEqualTo(413);
        var response = http("POST", "/api/memories", "{\"type\":\"FACT\",\"content\":\"Server has 32 GB RAM\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        var id = json.readTree(response.body()).path("id").asString();
        assertThat(http("GET", "/api/memories/" + id, null).statusCode()).isEqualTo(200);
        assertThat(http("DELETE", "/api/memories/" + id, null).statusCode()).isEqualTo(204);
        assertThat(http("GET", "/api/memories/" + id, null).statusCode()).isEqualTo(404);
    }
    @Test void concurrentUsageIsAtomicAndDoesNotConflictWithPreparedWrites() throws Exception {
        var memory = create(MemoryType.FACT, "Server has 32 GB RAM");
        long generation = writes.generation();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> service.recordUsage(List.of(memory.id(), memory.id()))));
            for (var future : futures) assertThat(future.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(writes.generation()).isEqualTo(generation);
        assertThat(service.get(memory.id()).version()).isEqualTo(memory.version());
        assertThat(service.get(memory.id()).accessCount()).isEqualTo(20);
        var edited = writes.commit(generation, () -> repository.replace(memory,
                new MemoryDraft(memory.type(), memory.content(), 0.8, memory.confidence(), memory.tags(), memory.metadata(),
                        memory.source(), memory.sourceConversationId(), null), null, false));
        assertThat(edited.accessCount()).isEqualTo(20);
        assertThat(edited.lastAccessedAt()).isNotNull();
        assertThat(service.recordUsage(List.of())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "CONTRADICTION"})
    void duplicateSurvivorRetainsOtherRelationsAndUsageDoesNotAbortAdmission(String relation) {
        var old = create(MemoryType.FACT, "Server has 16 GB RAM");
        var duplicate = create(MemoryType.FACT, "Server memory capacity is 32 GB");
        llm.responder = input -> {
            // Simulates an acknowledgement arriving while relation inference is in flight.
            service.recordUsage(List.of(duplicate.id()));
            String id = json.readTree(input).path("existing").path("id").asString();
            return json.writeValueAsString(Map.of("relation", id.equals(old.id().toString()) ? relation : "DUPLICATE", "reason", "supported relation"));
        };
        var result = admission.admit(new MemoryDraft(MemoryType.FACT, "Server has 32 GB RAM", 0.7, 0.95,
                List.of("server"), Map.of("project", "memory"), "EXTRACTED", "review-regression", "32 GB RAM"));
        assertThat(result.memory().id()).isEqualTo(duplicate.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memories", Long.class)).isEqualTo(2);
        assertThat(service.get(old.id()).superseded()).isEqualTo(relation.equals("UPDATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_links WHERE from_id=? AND to_id=? AND relation=?",
                Long.class, duplicate.id(), old.id(), relation.equals("UPDATE") ? "SUPERSEDES" : "CONTRADICTS")).isEqualTo(1);
        assertThat(service.get(duplicate.id()).accessCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_evidence WHERE memory_id=? AND conversation_id='review-regression'",
                Long.class, duplicate.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "archive", "supersede", "delete"})
    void sourceChangesInvalidateAllDerivedMemoriesAndRequeueSurvivingSources(String change) {
        var source = create(MemoryType.PROJECT, "Added pgvector to memory project");
        var other = create(MemoryType.PROJECT, "Implemented semantic search");
        var derived = derived("Building memory search with pgvector", List.of(source, other));
        var descendant = derived("Memory project progress summary", List.of(derived, other));
        var unrelated = create(MemoryType.PREFERENCE, "User prefers Java");
        service.recordUsage(List.of(source.id()));
        assertThat(service.get(derived.id()).active()).isTrue();
        switch (change) {
            case "edit" -> service.patch(source.id(), new Requests.Patch(source.version(), null, "Replaced pgvector in memory project", null, null, null, null, null));
            case "archive" -> service.archive(source.id(), new Requests.Archive(source.version(), true));
            case "supersede" -> writes.commit(writes.generation(), () -> { repository.supersede(source, "SUPERSEDED"); return null; });
            case "delete" -> service.delete(source.id());
            default -> throw new AssertionError(change);
        }
        for (var invalid : List.of(derived, descendant)) {
            var current = service.get(invalid.id());
            assertThat(current.stale()).isTrue();
            assertThat(current.active()).isFalse();
            assertThat(current.version()).isEqualTo(invalid.version() + 1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_revisions WHERE memory_id=? AND reason='SOURCE_CHANGED'",
                    Long.class, invalid.id())).isEqualTo(1);
        }
        assertThat(service.get(unrelated.id()).active()).isTrue();
        assertThat(service.list(20, 0, false)).noneMatch(Memory::stale);
        assertThat(search.search(query("memory project", false, false))).noneMatch(result -> result.memory().stale());
        assertThat(service.list(20, 0, true)).anyMatch(Memory::stale);
        assertThat(repository.consolidationBatch()).extracting(Memory::id).contains(other.id());
        assertThat(repository.exact(derived.type(), derived.content(), "memory")).isEmpty();
        assertThat(service.recordUsage(List.of(derived.id(), descendant.id()))).isZero();
        assertThat(service.archive(derived.id(), new Requests.Archive(service.get(derived.id()).version(), false)).active()).isFalse();
        if (change.equals("edit")) {
            var refreshed = derived(derived.content(), List.of(service.get(source.id()), other));
            assertThat(refreshed.id()).isNotEqualTo(derived.id());
            assertThat(refreshed.active()).isTrue();
        }
    }

    @Test void derivedInvalidationRollsBackWithFailedSourceWrite() {
        var source = create(MemoryType.PROJECT, "Added pgvector");
        var other = create(MemoryType.PROJECT, "Added search");
        var summary = derived("Building semantic memory search", List.of(source, other));
        long generation = writes.generation();
        assertThatThrownBy(() -> writes.commit(generation, () -> {
            repository.supersede(source, "SUPERSEDED");
            throw MemoryException.conflict();
        })).isInstanceOf(MemoryException.class);
        assertThat(writes.generation()).isEqualTo(generation);
        assertThat(service.get(source.id()).active()).isTrue();
        assertThat(service.get(summary.id()).active()).isTrue();
        assertThat(service.get(summary.id()).stale()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_revisions WHERE reason='SOURCE_CHANGED'", Long.class)).isZero();
    }

    private Memory derived(String content, List<Memory> sources) {
        var draft = new MemoryDraft(MemoryType.PROJECT, content, 0.7, 0.95, List.of("technical"),
                Map.of("project", "memory"), "CONSOLIDATED", null, null);
        var vector = embeddings.embed(content);
        return writes.commit(writes.generation(), () -> {
            var memory = repository.insert(draft, vector, null, sources.getFirst().id(), UUID.randomUUID());
            for (var source : sources) repository.link(memory.id(), source.id(), "CONSOLIDATED_FROM");
            var ids = new ArrayList<UUID>(); sources.forEach(source -> ids.add(source.id())); ids.add(memory.id());
            repository.markConsolidated(ids);
            return memory;
        });
    }
    @Test void mcpTransportInitializesDiscoversToolsAndListsMemories() throws Exception {
        var initialized = mcpHttp(json.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "publication-test", "version", "1.0")))), null, null);
        assertThat(initialized.statusCode()).isEqualTo(200);
        var initialization = mcpResponse(initialized);
        assertThat(initialization.has("error")).isFalse();
        var protocol = initialization.path("result").path("protocolVersion").asString();
        assertThat(protocol).isNotBlank();
        var session = initialized.headers().firstValue("Mcp-Session-Id").orElse(null);

        var notification = mcpHttp("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session, protocol);
        assertThat(notification.statusCode()).isEqualTo(202);

        var discovered = mcpHttp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session, protocol);
        assertThat(discovered.statusCode()).isEqualTo(200);
        var tools = mcpResponse(discovered);
        assertThat(tools.has("error")).isFalse();
        var names = new HashSet<String>();
        tools.path("result").path("tools").forEach(tool -> names.add(tool.path("name").asString()));
        assertThat(names).containsExactlyInAnyOrder("memory_search", "memory_ingest", "memory_get", "memory_list", "memory_history");

        var listed = mcpHttp("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_list\",\"arguments\":{\"limit\":2}}}", session, protocol);
        assertThat(listed.statusCode()).isEqualTo(200);
        var result = mcpResponse(listed);
        assertThat(result.has("error")).isFalse();
        assertThat(result.path("result").path("isError").asBoolean()).isFalse();
        var content = result.path("result").path("content");
        assertThat(content.size()).isGreaterThan(0);
        assertThat(json.readTree(content.get(0).path("text").asString()).size()).isZero();
    }

    private HttpResponse<String> mcpHttp(String body, String session, String protocol) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) request.header("Mcp-Session-Id", session);
        if (protocol != null) request.header("MCP-Protocol-Version", protocol);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private tools.jackson.databind.JsonNode mcpResponse(HttpResponse<String> response) throws Exception {
        var payload = response.body().lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).strip()).filter(line -> !line.isEmpty())
                .findFirst().orElse(response.body());
        return json.readTree(payload);
    }

    private HttpResponse<String> http(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
