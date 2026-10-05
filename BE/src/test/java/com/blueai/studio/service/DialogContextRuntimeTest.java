package com.blueai.studio.service;

import com.blueai.auth.model.User;
import com.blueai.rag.context.ContextSourceType;
import com.blueai.rag.context.ConversationContextFragment;
import com.blueai.rag.context.ResolvedConversationContext;
import com.blueai.rag.memory.model.MemoryScope;
import com.blueai.rag.memory.model.MemoryStatus;
import com.blueai.rag.memory.model.UserMemory;
import com.blueai.studio.dto.BotStudioDtos.*;
import com.blueai.studio.model.DialogContextReusePolicy;
import com.blueai.studio.model.DialogNextStep;
import com.blueai.studio.model.DialogNodeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DialogContextRuntimeTest {
    private final DialogRuntimeService runtime = new DialogRuntimeService();
    private final Map<String, DialogContextBridge.Suggestion> hints = Map.of("country",
            new DialogContextBridge.Suggestion("Nhật", "Phiên được liên kết: khách demo"));

    @Test void offersHintButNeverUsesItUntilConfirmed() {
        var offered = runtime.evaluate(snapshot(DialogContextReusePolicy.CONFIRMED_ONLY), null, "tư vấn esim", hints);
        assertThat(offered.trace().route()).isEqualTo("CONTEXT_CONFIRMATION");
        assertThat(offered.context().variables()).doesNotContainKey("country");
        assertThat(offered.context().suggestedVariables()).containsEntry("country", "Nhật");
        var confirmed = runtime.evaluate(snapshot(DialogContextReusePolicy.CONFIRMED_ONLY), offered.context(), "xác nhận", hints);
        assertThat(confirmed.message()).isEqualTo("Tư vấn cho Nhật");
        assertThat(confirmed.trace().confirmedEntities()).containsEntry("country", "Nhật");
    }

    @Test void explicitNewFactOverridesTheHistoricalHint() {
        var bot = snapshot(DialogContextReusePolicy.CONFIRMED_ONLY);
        var offered = runtime.evaluate(bot, null, "tư vấn esim", hints);
        var changed = runtime.evaluate(bot, offered.context(), "Hàn", hints);
        assertThat(changed.message()).isEqualTo("Tư vấn cho Hàn");
        assertThat(changed.context().suggestedVariables()).isEmpty();
    }

    @Test void clientCannotForgeTheProvenanceOfAValidHint() {
        var bot = snapshot(DialogContextReusePolicy.CONFIRMED_ONLY);
        var offered = runtime.evaluate(bot, null, "tư vấn esim", hints);
        var supplied = new PreviewContext(offered.context().pendingIntent(), offered.context().pendingEntity(),
                offered.context().lastIntent(), offered.context().variables(), offered.context().waitingNode(),
                offered.context().suggestedVariables(), "Nguồn giả từ client");
        var confirmed = runtime.evaluate(bot, supplied, "xác nhận", hints);
        assertThat(confirmed.trace().contextSource()).isEqualTo(hints.get("country").source());
    }

    @Test void rejectionAsksForFreshValueWithoutReoffering() {
        var bot = snapshot(DialogContextReusePolicy.CONFIRMED_ONLY);
        var offered = runtime.evaluate(bot, null, "tư vấn esim", hints);
        var rejected = runtime.evaluate(bot, offered.context(), "không", hints);
        assertThat(rejected.message()).isEqualTo("Khách đi quốc gia nào?");
        assertThat(rejected.context().suggestedVariables()).isEmpty();
        assertThat(runtime.evaluate(bot, rejected.context(), "Hàn", hints).message()).isEqualTo("Tư vấn cho Hàn");
    }

    @Test void policyOffAndExpiredHintsCannotBeConfirmed() {
        assertThat(runtime.evaluate(snapshot(DialogContextReusePolicy.NONE), null, "tư vấn esim", hints).message())
                .isEqualTo("Khách đi quốc gia nào?");
        var bot = snapshot(DialogContextReusePolicy.CONFIRMED_ONLY);
        var offered = runtime.evaluate(bot, null, "tư vấn esim", hints);
        var expired = runtime.evaluate(bot, offered.context(), "xác nhận", Map.of());
        assertThat(expired.context().variables()).doesNotContainKey("country");
        assertThat(expired.message()).doesNotContain("Tư vấn cho Nhật");
    }

    @Test void topicSwitchAndCancellationDiscardPendingHint() {
        var bot = snapshot(DialogContextReusePolicy.CONFIRMED_ONLY);
        var offered = runtime.evaluate(bot, null, "tư vấn esim", hints);
        assertThat(runtime.evaluate(bot, offered.context(), "xin chào", hints).message()).isEqualTo("Chào sales");
        var cancelled = runtime.evaluate(bot, offered.context(), "hủy", hints);
        assertThat(cancelled.context().pendingEntity()).isNull();
        assertThat(cancelled.context().suggestedVariables()).isEmpty();
    }

    @Test void unrecognizedReplyRetainsConfirmationNotSilentAcceptance() {
        var bot = snapshot(DialogContextReusePolicy.CONFIRMED_ONLY);
        var offered = runtime.evaluate(bot, null, "tư vấn esim", hints);
        var unsure = runtime.evaluate(bot, offered.context(), "mình chưa rõ", hints);
        assertThat(unsure.trace().route()).isEqualTo("CONTEXT_CONFIRMATION");
        assertThat(unsure.context().variables()).doesNotContainKey("country");
    }

    @Test void bridgeIgnoresProposedExpiredAutomaticOrConflictingContext() {
        var bridge = new DialogContextBridge();
        var bot = snapshot(DialogContextReusePolicy.CONFIRMED_ONLY);
        var confirmed = memory("dialog.country", "Nhật", MemoryStatus.CONFIRMED, null);
        assertThat(bridge.suggestions(bot, context(List.of(confirmed), List.of())))
                .containsKey("country");
        assertThat(bridge.suggestions(bot, context(List.of(
                memory("dialog.country", "Nhật", MemoryStatus.PROPOSED, null),
                memory("dialog.country", "Nhật", MemoryStatus.CONFIRMED, Instant.now().minusSeconds(1))), List.of())))
                .isEmpty();
        assertThat(bridge.suggestions(bot, context(List.of(confirmed), List.of(
                new ConversationContextFragment(ContextSourceType.LINKED_CONVERSATION, "linked", "other", "Hàn", 1)))))
                .isEmpty();
        assertThat(bridge.suggestions(bot, context(List.of(), List.of(
                new ConversationContextFragment(ContextSourceType.RELATED_HISTORY, "history", "old", "Nhật", 1)))))
                .isEmpty();
    }

    @Test void linkedContextAndLegacyJsonDefaultsAreSafe() throws Exception {
        var bridge = new DialogContextBridge();
        assertThat(bridge.suggestions(snapshot(DialogContextReusePolicy.CONFIRMED_ONLY), context(List.of(), List.of(
                new ConversationContextFragment(ContextSourceType.LINKED_CONVERSATION, "source", "Khách mẫu", "Khách đi Nhật", 1)))))
                .containsKey("country");
        var old = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"pendingIntent\":null,\"pendingEntity\":null,\"lastIntent\":null,\"variables\":{}}", PreviewContext.class);
        assertThat(old.suggestedVariables()).isEmpty();
        assertThat(runtime.evaluate(snapshot(DialogContextReusePolicy.NONE), old, "xin chào").message()).isEqualTo("Chào sales");
    }

    @Test void accentedNodeNamesRemainDistinctForParentsAndAutomaticTraversal() {
        var bot = new BotSnapshot(2, "bot", "owner", "Sales", "", 1, "vi-VN", "Asia/Ho_Chi_Minh", true, "", null,
                List.of(new IntentInput("consult", "", List.of("tư vấn esim", "hỏi esim"))), List.of(), List.of(
                new DialogNodeInput("Bao gia", DialogNodeType.ROUTE, null, "consult", null, null, null,
                        "Bắt đầu báo giá", null, DialogNextStep.SKIP_USER_INPUT, null, null, 10),
                new DialogNodeInput("Nhánh khác", DialogNodeType.ROUTE, "Báo giá", "consult", null, null, null,
                        "Không được vào nhánh này", null, DialogNextStep.WAIT_FOR_REPLY, null, null, 20),
                new DialogNodeInput("Báo giá", DialogNodeType.ROUTE, null, "consult", null, null, null,
                        "Báo giá đúng", null, DialogNextStep.WAIT_FOR_REPLY, null, null, 30)));
        var result = runtime.evaluate(bot, null, "tư vấn esim");
        assertThat(result.message()).isEqualTo("Bắt đầu báo giá\n\nBáo giá đúng");
        assertThat(result.trace().route()).isEqualTo("DIALOG");
        assertThat(result.trace().visitedNodes()).containsExactly("Bao gia", "Báo giá");
    }

    private UserMemory memory(String key, String value, MemoryStatus status, Instant expiry) {
        return new UserMemory((User) null, key, value, MemoryScope.USER, null, null, status, expiry);
    }

    private ResolvedConversationContext context(List<UserMemory> memories, List<ConversationContextFragment> fragments) {
        return new ResolvedConversationContext("tư vấn", List.of(), "", memories, fragments, List.of(), false);
    }

    private BotSnapshot snapshot(DialogContextReusePolicy policy) {
        return new BotSnapshot(2, "bot", "owner", "Sales", "", 1, "vi-VN", "Asia/Ho_Chi_Minh", true, "", null,
                List.of(new IntentInput("consult", "", List.of("tư vấn esim", "hỏi esim")),
                        new IntentInput("greeting", "", List.of("xin chào", "chào bạn"))),
                List.of(new EntityInput("country", "", List.of(new EntityValueInput("Nhật", List.of("Nhật Bản")),
                        new EntityValueInput("Hàn", List.of("Hàn Quốc"))))),
                List.of(new DialogNodeInput("Consult", DialogNodeType.ROUTE, null, "consult", null, null, "country",
                                "Tư vấn cho {{country}}", "Khách đi quốc gia nào?", DialogNextStep.WAIT_FOR_REPLY, null, null, 10, policy),
                        new DialogNodeInput("Hello", DialogNodeType.ROUTE, null, "greeting", null, null, null,
                                "Chào sales", null, DialogNextStep.WAIT_FOR_REPLY, null, null, 20),
                        new DialogNodeInput("Fallback", DialogNodeType.FALLBACK, null, null, null, null, null,
                                "Chưa hiểu", null, DialogNextStep.WAIT_FOR_REPLY, null, null, 99)));
    }
}
