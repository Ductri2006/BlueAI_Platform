package com.blueai.rag.conversation.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMessageOrderingTest {

    @Test
    void touchAndFlushCannotMoveAllocatedMessageClockBackwards() {
        Conversation conversation = new Conversation(null, "test");
        Instant first = conversation.nextMessageTimestamp(Instant.now().plusSeconds(1));
        conversation.touch();
        conversation.onUpdate();
        assertThat(conversation.nextMessageTimestamp(first)).isAfter(first);
    }

    @Test
    void tiedDatabaseClockTicksStillProduceMonotonicMessageOrder() {
        Conversation conversation = new Conversation(null, "test");
        Instant databaseTick = Instant.parse("2026-09-24T00:00:00.123456Z");

        Instant first = conversation.nextMessageTimestamp(databaseTick);
        Instant second = conversation.nextMessageTimestamp(databaseTick);

        assertThat(second).isEqualTo(first.plusNanos(1_000));
        assertThat(second).isAfter(first);
    }
}
