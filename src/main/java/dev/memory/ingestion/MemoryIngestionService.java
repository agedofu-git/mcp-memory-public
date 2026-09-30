package dev.memory.ingestion;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.memory.config.MemoryProperties;
import dev.memory.controller.Requests.Ingest;
import dev.memory.domain.MemoryDraft;
import dev.memory.service.MemoryException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class MemoryIngestionService {
    private static final Logger log = LoggerFactory.getLogger(MemoryIngestionService.class);
    public record Outcome(int candidateIndex, String action, UUID memoryId,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {}
    public record Result(boolean complete, int extracted, List<Outcome> outcomes) {}
    private final MemoryExtractor extractor;
    private final MemoryJudge judge;
    private final MemoryAdmission admission;
    private final boolean logContent;
    public MemoryIngestionService(MemoryExtractor extractor, MemoryJudge judge, MemoryAdmission admission, MemoryProperties properties) {
        this.extractor = extractor; this.judge = judge; this.admission = admission; logContent = properties.logContent();
    }
    public Result ingest(Ingest request) {
        var candidates = extractor.extract(request);
        var outcomes = new ArrayList<Outcome>();
        log.info("event=candidates_extracted count={}", candidates.size());
        boolean complete = true;
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            if (logContent) log.atDebug().addKeyValue("content", candidate.content()).log("candidate_content");
            try {
                var judgment = judge.judge(candidate, request.userMessage());
                if (!judgment.keep()) {
                    log.debug("event=candidate_rejected index={}", index);
                    if (logContent) log.atDebug().addKeyValue("reason", judgment.reason()).log("candidate_rejection_reason");
                    outcomes.add(new Outcome(index, "REJECT", null, request.debug() ? judgment.reason() : null));
                    continue;
                }
                Map<String, Object> metadata = request.project() == null ? Map.of() : Map.of("project", request.project());
                var result = admission.admit(MemoryDraft.extracted(candidate, request.conversationId(), metadata));
                outcomes.add(new Outcome(index, result.action(), result.memory().id(), null));
            } catch (MemoryException exception) {
                complete = false;
                outcomes.add(new Outcome(index, "ERROR", null, exception.kind().name()));
            } catch (DataAccessException exception) {
                complete = false;
                outcomes.add(new Outcome(index, "ERROR", null, "DATABASE_FAILURE"));
            }
        }
        return new Result(complete, candidates.size(), List.copyOf(outcomes));
    }
}
