package com.example.im.message.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DedupGenerationProvisionerTest {
    private static final Instant NOW = Instant.ofEpochSecond(82_976L * 21_600L + 3_600L);

    @Test
    void keepsTheWholeRetryWindowAndTheNextGenerationReady() {
        assertThat(DedupGenerationProvisioner.requiredGenerations(NOW, Duration.ofHours(24)))
                .containsExactly(82_972L, 82_973L, 82_974L, 82_975L, 82_976L, 82_977L);
    }

    @Test
    void provisionsEveryRequiredGeneration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DedupGenerationProvisioner provisioner = new DedupGenerationProvisioner(
                jdbc, Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC));

        provisioner.provisionRequiredGenerations();

        for (long generation = 82_972L; generation <= 82_977L; generation++) {
            verify(jdbc).update("CALL provision_dedup_generation(?)", generation);
        }
    }
}
