package com.blueai.studio.service;

import com.blueai.studio.config.WidgetProtectionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetConversationContextStoreTest {

    @Test
    void replaysOnlyTheSameRecentMessageAndCountsRepeatedAttempts() {
        WidgetProtectionProperties properties = new WidgetProtectionProperties();
        properties.setDuplicateReplayLimit(2);
        WidgetConversationContextStore store = new WidgetConversationContextStore(
                null,
                properties,
                new ObjectMapper()
        );

        store.recordResponse("session-1", "digest-a", "{\"requestId\":\"first\"}", "Cửa hàng mở cửa mấy giờ?");

        WidgetConversationContextStore.DuplicateResult first = store.recordDuplicateAttempt("session-1", "digest-a");
        WidgetConversationContextStore.DuplicateResult second = store.recordDuplicateAttempt("session-1", "digest-a");
        WidgetConversationContextStore.DuplicateResult changed = store.recordDuplicateAttempt("session-1", "digest-b");

        assertThat(first.isDuplicate()).isTrue();
        assertThat(first.repeatCount()).isEqualTo(1);
        assertThat(second.repeatCount()).isEqualTo(2);
        assertThat(changed.isDuplicate()).isFalse();
    }

    @Test
    void recognizesClarificationLanguageWithoutTreatingItAsDuplicate() {
        WidgetConversationContextStore store = new WidgetConversationContextStore(
                null,
                new WidgetProtectionProperties(),
                new ObjectMapper()
        );

        assertThat(store.isClarification("Mình chưa hiểu, hãy cho một ví dụ cụ thể.")).isTrue();
        assertThat(store.isClarification("Tôi muốn mua gói mới.")).isFalse();
    }
}
