package com.example.im.routing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class XxHash64Test {
    @Test
    void matchesPublishedEmptyInputVector() {
        long hash = XxHash64.hash(new byte[0], 0);
        assertThat(Long.toUnsignedString(hash, 16)).isEqualTo("ef46db3751d8e999");
    }

    @Test
    void isStableForRoutingInput() {
        long hash = XxHash64.hash("im-routing-v1".getBytes(StandardCharsets.UTF_8), 0);
        assertThat(Long.toUnsignedString(hash, 16)).isEqualTo("50efa763c4c44ce6");
    }
}

