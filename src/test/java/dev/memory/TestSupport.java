package dev.memory;

import dev.memory.config.MemoryProperties;
import dev.memory.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

public final class TestSupport {
    private TestSupport() {}
    public static MemoryProperties properties() {
        try {
            var sources = new YamlPropertySourceLoader().load("test", new ClassPathResource("application.yml"));
            // Local provider settings must not alter deterministic unit-test fixtures.
            var environment = new org.springframework.mock.env.MockEnvironment();
            sources.forEach(source -> environment.getPropertySources().addLast(source));
            return Binder.get(environment).bind("memory", MemoryProperties.class).orElseThrow(IllegalStateException::new);
        } catch (java.io.IOException exception) { throw new RuntimeException(exception); }
    }
    public static Memory memory(MemoryType type, String content, Instant created, double importance, long accesses) {
        return new Memory(UUID.randomUUID(), type, content, TextNormalizer.normalize(content), created, created, null,
                "EXTRACTED", "conversation-1", importance, 0.95, accesses, List.of("server"), Map.of(),
                true, false, false, false, null, null, null, "test", "test-model", 3, "1", 0);
    }
    public static MemoryDraft draft(String content) {
        return new MemoryDraft(MemoryType.FACT, content, 0.7, 0.95, List.of("server"), Map.of(), "EXTRACTED", "conversation-2", content);
    }
}
