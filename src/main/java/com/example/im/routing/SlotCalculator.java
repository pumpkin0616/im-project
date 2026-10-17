package com.example.im.routing;

import com.example.im.config.ImProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class SlotCalculator {
    private final int slotCount;
    private final long seed;

    @Autowired
    public SlotCalculator(ImProperties properties) {
        this(properties.routing().slotCount(), properties.routing().hashSeed());
    }

    SlotCalculator(int slotCount, long seed) {
        if (slotCount <= 0 || Integer.bitCount(slotCount) != 1) {
            throw new IllegalArgumentException("slotCount must be a positive power of two");
        }
        this.slotCount = slotCount;
        this.seed = seed;
    }

    public int slot(UUID conversationId) {
        long hash = XxHash64.hash(UuidCodec.toBytes(conversationId), seed);
        return (int) (hash & (slotCount - 1L));
    }
}

