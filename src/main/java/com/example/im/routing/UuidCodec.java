package com.example.im.routing;

import java.nio.ByteBuffer;
import java.util.UUID;

public final class UuidCodec {
    private UuidCodec() {
    }

    public static byte[] toBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    public static UUID fromBytes(byte[] value) {
        if (value.length != 16) {
            throw new IllegalArgumentException("UUID binary value must contain 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}

