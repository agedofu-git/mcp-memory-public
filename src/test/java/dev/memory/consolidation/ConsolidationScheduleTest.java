package dev.memory.consolidation;

import dev.memory.TestSupport;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConsolidationScheduleTest {
    @Test void scheduledRunUsesConfiguredArchivingPolicy() {
        var service = mock(MemoryConsolidationService.class);
        new ConsolidationSchedule(service, TestSupport.properties()).sleep();
        verify(service).consolidate(true);
    }
}
