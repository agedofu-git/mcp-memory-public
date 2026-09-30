package dev.memory.config;

import dev.memory.TestSupport;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConfigurationTest {
    @Test void applicationYamlBindsAndValidates() {
        var properties = TestSupport.properties();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
        assertThat(properties.embedding().dimensions()).isEqualTo(768);
        assertThat(properties.ranking().candidatePool()).isEqualTo(50);
        assertThat(properties.ranking().minimumScore()).isZero();
        assertThat(properties.ranking().minimumSemanticScore()).isZero();
        assertThat(properties.llm().timeout().toSeconds()).isEqualTo(60);
        assertThat(properties.consolidation().archiveOnSchedule()).isTrue();
        assertThat(properties.maxRequestBytes()).isEqualTo(262144);
    }
}
