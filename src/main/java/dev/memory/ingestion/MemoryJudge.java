package dev.memory.ingestion;

import dev.memory.config.MemoryProperties;
import dev.memory.domain.MemoryCandidate;
import dev.memory.llm.StructuredLlm;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class MemoryJudge {
    public record Judgment(@NotNull Boolean keep, @NotBlank @Size(max = 1000) String reason) {}
    private final StructuredLlm llm;
    private final MemoryProperties.Extraction settings;
    public MemoryJudge(StructuredLlm llm, MemoryProperties properties) { this.llm = llm; settings = properties.extraction(); }
    public Judgment judge(MemoryCandidate candidate, String userMessage) {
        if (candidate.confidence() < settings.minimumConfidence()) return new Judgment(false, "below_minimum_confidence");
        if (candidate.importance() < settings.minimumImportance()) return new Judgment(false, "below_minimum_importance");
        String evidence = normalizeWhitespace(candidate.evidence());
        String source = normalizeWhitespace(userMessage);
        if (evidence.isEmpty() || !source.contains(evidence)) return new Judgment(false, "evidence_not_in_user_message");
        return llm.call("memory-judge", Map.of("candidate", candidate, "userMessage", userMessage), Judgment.class);
    }

    private static String normalizeWhitespace(String value) {
        return Objects.requireNonNullElse(value, "").strip().replaceAll("\\s+", " ");
    }
}
