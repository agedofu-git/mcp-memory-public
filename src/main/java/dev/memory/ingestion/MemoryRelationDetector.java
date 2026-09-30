package dev.memory.ingestion;

import dev.memory.domain.*;
import dev.memory.llm.StructuredLlm;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MemoryRelationDetector {
    private static final Logger log = LoggerFactory.getLogger(MemoryRelationDetector.class);
    public record Classification(@NotNull MemoryRelation relation, @NotBlank @Size(max = 1000) String reason) {}
    private final StructuredLlm llm;
    public MemoryRelationDetector(StructuredLlm llm) { this.llm = llm; }
    public Classification classify(MemoryDraft candidate, Memory existing) {
        if (candidate.type() == existing.type() && TextNormalizer.normalize(candidate.content()).equals(existing.normalizedContent()))
            return new Classification(MemoryRelation.DUPLICATE, "normalized_text_match");
        var result = llm.call("contradiction-detection", Map.of("candidate", candidate, "existing", existing), Classification.class);
        log.info("event=relation_classified existingId={} relation={}", existing.id(), result.relation());
        return result;
    }
}
