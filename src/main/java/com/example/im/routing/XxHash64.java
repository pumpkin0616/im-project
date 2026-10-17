package com.example.im.routing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal xxHash64 implementation used by every routing participant. */
public final class XxHash64 {
    private static final long PRIME1 = 0x9E3779B185EBCA87L;
    private static final long PRIME2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME3 = 0x165667B19E3779F9L;
    private static final long PRIME4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME5 = 0x27D4EB2F165667C5L;

    private XxHash64() {
    }

    public static long hash(byte[] input, long seed) {
        ByteBuffer buffer = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        long hash;

        if (input.length >= 32) {
            long v1 = seed + PRIME1 + PRIME2;
            long v2 = seed + PRIME2;
            long v3 = seed;
            long v4 = seed - PRIME1;

            while (buffer.remaining() >= 32) {
                v1 = round(v1, buffer.getLong());
                v2 = round(v2, buffer.getLong());
                v3 = round(v3, buffer.getLong());
                v4 = round(v4, buffer.getLong());
            }

            hash = Long.rotateLeft(v1, 1)
                    + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12)
                    + Long.rotateLeft(v4, 18);
            hash = mergeRound(hash, v1);
            hash = mergeRound(hash, v2);
            hash = mergeRound(hash, v3);
            hash = mergeRound(hash, v4);
        } else {
            hash = seed + PRIME5;
        }

        hash += input.length;

        while (buffer.remaining() >= 8) {
            long lane = round(0, buffer.getLong());
            hash ^= lane;
            hash = Long.rotateLeft(hash, 27) * PRIME1 + PRIME4;
        }
        if (buffer.remaining() >= 4) {
            hash ^= Integer.toUnsignedLong(buffer.getInt()) * PRIME1;
            hash = Long.rotateLeft(hash, 23) * PRIME2 + PRIME3;
        }
        while (buffer.hasRemaining()) {
            hash ^= Byte.toUnsignedLong(buffer.get()) * PRIME5;
            hash = Long.rotateLeft(hash, 11) * PRIME1;
        }

        hash ^= hash >>> 33;
        hash *= PRIME2;
        hash ^= hash >>> 29;
        hash *= PRIME3;
        hash ^= hash >>> 32;
        return hash;
    }

    private static long round(long accumulator, long lane) {
        accumulator += lane * PRIME2;
        accumulator = Long.rotateLeft(accumulator, 31);
        return accumulator * PRIME1;
    }

    private static long mergeRound(long accumulator, long value) {
        accumulator ^= round(0, value);
        return accumulator * PRIME1 + PRIME4;
    }
}

