package dev.memory.ingestion;

import dev.memory.TestSupport;
import dev.memory.controller.Requests;
import dev.memory.domain.*;
import dev.memory.llm.*;
import dev.memory.service.MemoryException;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class MemoryPipelineTest {
    private final Queue<String> responses = new ArrayDeque<>();
    private int calls;
    private StructuredLlm structured;
    private jakarta.validation.ValidatorFactory validatorFactory;
    @BeforeEach void setup() throws Exception {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        structured = new StructuredLlm((prompt, input) -> { calls++; return responses.remove(); }, validatorFactory.getValidator(), JsonMapper.builder().build());
    }
    @AfterEach void close() { validatorFactory.close(); }
    private MemoryCandidate candidate(double importance, double confidence, String evidence) {
        return new MemoryCandidate(MemoryType.FACT, "The user's server has 32 GB RAM.", importance, confidence, List.of("server"), evidence);
    }
    @Test void parsesTypedExtractionAndAllowsEmptyResults() {
        responses.add("""
                {"memories":[{"type":"FACT","content":"The user's server has 32 GB RAM.","importance":0.7,"confidence":0.98,"tags":["server"],"evidence":"32 GB RAM"}]}
                """);
        var extractor = new MemoryExtractor(structured, TestSupport.properties());
        var request = new Requests.Ingest("conversation-1", "I upgraded to 32 GB RAM", null, null, false);
        assertThat(extractor.extract(request)).singleElement().satisfies(memory -> assertThat(memory.type()).isEqualTo(MemoryType.FACT));
        responses.add("{\"memories\":[]}");
        assertThat(extractor.extract(request)).isEmpty();
    }
    @Test void rejectsMalformedMissingAndOutOfRangeModelFields() {
        var extractor = new MemoryExtractor(structured, TestSupport.properties());
        var request = new Requests.Ingest("conversation-1", "hello", null, null, false);
        for (String response : List.of("not json", "null", "{}", "{\"memories\":[null]}",
                "{\"memories\":[],\"surprise\":true}", "{\"memories\":[]} {}",
                "{\"memories\":[{\"type\":\"INVALID\"}]}",
                "{\"memories\":[{\"type\":\"FACT\",\"content\":\"x\",\"importance\":4,\"confidence\":1,\"tags\":[],\"evidence\":\"x\"}]}")) {
            responses.add(response);
            assertThatThrownBy(() -> extractor.extract(request)).isInstanceOf(MemoryException.class);
        }
    }
    @Test void cheapUsefulnessAndEvidenceChecksAvoidProviderCalls() {
        var judge = new MemoryJudge(structured, TestSupport.properties());
        assertThat(judge.judge(candidate(0.1, 0.99, "32 GB RAM"), "32 GB RAM").keep()).isFalse();
        assertThat(judge.judge(candidate(0.7, 0.1, "32 GB RAM"), "32 GB RAM").keep()).isFalse();
        assertThat(judge.judge(candidate(0.7, 0.95, "invented source"), "32 GB RAM").keep()).isFalse();
        assertThat(judge.judge(candidate(0.7, 0.95, ""), "32 GB RAM").keep()).isFalse();
        assertThat(calls).isZero();
    }
    @Test void evidenceComparisonNormalizesWhitespace() {
        responses.add("{\"keep\":true,\"reason\":\"durable fact\"}");
        var candidate = candidate(0.7, 0.95, "32   GB\nRAM");
        assertThat(new MemoryJudge(structured, TestSupport.properties())
                .judge(candidate, "I upgraded to 32 GB RAM yesterday").keep()).isTrue();
        assertThat(calls).isOne();
    }
    @Test void judgeCanRejectTemporaryNoiseAndUnsupportedEntailment() {
        responses.add("{\"keep\":false,\"reason\":\"fleeting state\"}");
        var candidate = new MemoryCandidate(MemoryType.EPISODIC, "User is hungry", 0.4, 0.9, List.of(), "I'm hungry right now");
        assertThat(new MemoryJudge(structured, TestSupport.properties()).judge(candidate, "I'm hungry right now").keep()).isFalse();
    }
    @Test void identicalTextAvoidsRelationCallAndUpdateIsStronglyTyped() {
        var old = TestSupport.memory(MemoryType.FACT, "Server has 16 GB RAM", Instant.now(), 0.7, 0);
        var detector = new MemoryRelationDetector(structured);
        assertThat(detector.classify(TestSupport.draft("Server has 16 GB RAM"), old).relation()).isEqualTo(MemoryRelation.DUPLICATE);
        assertThat(calls).isZero();
        responses.add("{\"relation\":\"UPDATE\",\"reason\":\"explicit RAM upgrade\"}");
        assertThat(detector.classify(TestSupport.draft("Server has 32 GB RAM"), old).relation()).isEqualTo(MemoryRelation.UPDATE);
    }
    @Test void decisionPolicyNeverUsesSimilarityAloneToOverwriteFacts() {
        var old = TestSupport.memory(MemoryType.FACT, "Server has 16 GB RAM", Instant.now(), 0.7, 0);
        var draft = TestSupport.draft("Server has 32 GB RAM");
        var detector = new DuplicateDetector(TestSupport.properties());
        assertThat(detector.decide(draft, old, MemoryRelation.UNRELATED, 0.99)).isEqualTo(DuplicateDetector.Decision.STORE);
        assertThat(detector.decide(draft, old, MemoryRelation.UPDATE, 0.8)).isEqualTo(DuplicateDetector.Decision.SUPERSEDE);
        assertThat(detector.decide(draft, old, MemoryRelation.CONTRADICTION, 0.99)).isEqualTo(DuplicateDetector.Decision.LINK_CONTRADICTION);
        assertThat(detector.decide(draft, old, MemoryRelation.REFINEMENT, 0.8)).isEqualTo(DuplicateDetector.Decision.SUPERSEDE);
    }
    @Test void paraphrasedDuplicatesMergeAtLowerSimilarityInsteadOfAccumulating() {
        var old = TestSupport.memory(MemoryType.PREFERENCE, "User prefers Java.", Instant.now(), 0.7, 0);
        var draft = new MemoryDraft(MemoryType.PREFERENCE, "Java is the user's preferred language.", 0.7, 0.9, List.of("java"), Map.of(), "EXTRACTED", "c2", "I prefer Java");
        assertThat(new DuplicateDetector(TestSupport.properties()).decide(draft, old, MemoryRelation.DUPLICATE, 0.85)).isEqualTo(DuplicateDetector.Decision.MERGE);
    }
    @Test void normalizationPreservesMeaningfulSymbols() {
        assertThat(TextNormalizer.normalize(" C++ ")).isNotEqualTo(TextNormalizer.normalize("C"));
        assertThat(TextNormalizer.normalize("-16 GB")).isNotEqualTo(TextNormalizer.normalize("16 GB"));
        assertThat(TextNormalizer.normalize("  User  prefers JAVA ")).isEqualTo("user prefers java");
    }
}
