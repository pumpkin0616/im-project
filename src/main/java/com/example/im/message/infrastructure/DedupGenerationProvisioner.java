package com.example.im.message.infrastructure;

import com.example.im.config.ImProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
final class DedupGenerationProvisioner {
    private static final Logger log = LoggerFactory.getLogger(DedupGenerationProvisioner.class);
    private static final long GENERATION_SECONDS = Duration.ofHours(6).toSeconds();

    private final JdbcTemplate jdbc;
    private final Duration retryWindow;
    private final Clock clock;

    @Autowired
    DedupGenerationProvisioner(JdbcTemplate jdbc, ImProperties properties) {
        this(jdbc, properties.message().automaticRetryWindow(), Clock.systemUTC());
    }

    DedupGenerationProvisioner(JdbcTemplate jdbc, Duration retryWindow, Clock clock) {
        this.jdbc = jdbc;
        this.retryWindow = retryWindow;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void provisionAtStartup() {
        provisionRequiredGenerations();
    }

    @Scheduled(fixedDelayString = "${IM_DEDUP_PROVISION_INTERVAL_MS:300000}")
    void scheduledProvision() {
        try {
            provisionRequiredGenerations();
        } catch (RuntimeException exception) {
            log.error("Failed to provision rolling idempotency generations", exception);
        }
    }

    void provisionRequiredGenerations() {
        List<Long> generations = requiredGenerations(clock.instant(), retryWindow);
        for (long generation : generations) {
            jdbc.update("CALL provision_dedup_generation(?)", generation);
        }
        log.debug("Idempotency generations are ready: {}", generations);
    }

    static List<Long> requiredGenerations(Instant now, Duration retryWindow) {
        if (retryWindow == null || retryWindow.isNegative() || retryWindow.isZero()) {
            throw new IllegalArgumentException("retryWindow must be positive");
        }
        long current = Math.floorDiv(now.getEpochSecond(), GENERATION_SECONDS);
        long past = Math.floorDiv(retryWindow.getSeconds() + GENERATION_SECONDS - 1, GENERATION_SECONDS);
        List<Long> result = new ArrayList<>((int) Math.min(past + 2, Integer.MAX_VALUE));
        for (long generation = current - past; generation <= current + 1; generation++) {
            result.add(generation);
        }
        return List.copyOf(result);
    }
}
