package com.example.im.message.infrastructure;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

@Component
public final class UuidV7Generator {
    private final SecureRandom random;
    private final Clock clock;

    public UuidV7Generator() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    UuidV7Generator(SecureRandom random, Clock clock) {
        this.random = random;
        this.clock = clock;
    }

    public UUID next() {
        long unixMillis = clock.millis() & 0xFFFFFFFFFFFFL;
        int randomA = random.nextInt(1 << 12);
        long randomB = random.nextLong() & 0x3FFFFFFFFFFFFFFFL;

        long mostSignificant = (unixMillis << 16) | 0x7000L | randomA;
        long leastSignificant = 0x8000000000000000L | randomB;
        return new UUID(mostSignificant, leastSignificant);
    }
}

