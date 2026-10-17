package com.example.im.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MessageTableNamesTest {
    @Test
    void buildsOnlyWhitelistedIdentifiers() {
        MessageTableNames names = new MessageTableNames(3, 81234);

        assertThat(names.conversations()).isEqualTo("conversations_03");
        assertThat(names.messages()).isEqualTo("messages_03");
        assertThat(names.dedup()).isEqualTo("send_dedup_03_g81234");
    }

    @Test
    void rejectsInvalidGroup() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MessageTableNames(8, 1));
    }
}

