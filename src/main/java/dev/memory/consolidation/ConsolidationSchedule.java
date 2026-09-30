package dev.memory.consolidation;

import dev.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "memory.consolidation.schedule-enabled", havingValue = "true")
public class ConsolidationSchedule {
    private static final Logger log = LoggerFactory.getLogger(ConsolidationSchedule.class);
    private final MemoryConsolidationService service;
    private final boolean archiveOnSchedule;
    public ConsolidationSchedule(MemoryConsolidationService service, MemoryProperties properties) {
        this.service = service;
        archiveOnSchedule = properties.consolidation().archiveOnSchedule();
    }
    @Scheduled(cron = "${memory.consolidation.cron}")
    public void sleep() {
        try { service.consolidate(archiveOnSchedule); }
        catch (RuntimeException exception) { log.warn("event=scheduled_consolidation_failed", exception); }
    }
}
