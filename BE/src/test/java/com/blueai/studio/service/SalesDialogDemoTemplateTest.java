package com.blueai.studio.service;

import com.blueai.studio.dto.BotStudioDtos.PreviewContext;
import com.blueai.studio.dto.BotStudioDtos.RuntimeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SalesDialogDemoTemplateTest {
    private final DialogRuntimeService runtime = new DialogRuntimeService();
    private final SalesDialogDemoTemplate.Configuration template = SalesDialogDemoTemplate.load(new ObjectMapper());
    private final BotSnapshot bot = new BotSnapshot(2, "demo", "owner", "Demo sales", "DEMO", 1,
            "vi-VN", "Asia/Ho_Chi_Minh", false, null, null,
            template.intents(), template.entities(), template.dialogNodes());

    @Test
    void bundledConfigurationIsValidAndKeepsCrossSessionReuseOff() {
        assertThat(new DialogConfigurationValidator().analyze(template.dialogNodes(), template.entities()))
                .noneMatch(item -> item.severity() == DialogConfigurationValidator.Severity.ERROR);
        assertThat(template.dialogNodes()).hasSize(13).allMatch(node -> node.contextReusePolicy().name().equals("NONE"));
    }

    @Test
    void welcomeAndGreetingExplainDemoWithoutInventingTransactions() {
        assertThat(runtime.welcome(bot).message()).contains("DEMO SALES", "không có giá hay đơn hàng thật");
        assertThat(runtime.evaluate(bot, null, "xin chào").message()).contains("Sản phẩm A/B", "10, 20, 30, 50, 100");
    }

    @ParameterizedTest
    @CsvSource({"Hà Nội,08:00–21:00", "Sài Gòn,08:00–22:00", "Đà Nẵng,08:30–21:00"})
    void branchHoursRecognizeCanonicalValuesAndSynonyms(String branch, String hours) {
        var result = runtime.evaluate(bot, null, "hỏi giờ " + branch);
        assertThat(result.message()).contains("DEMO", hours, "giờ minh họa");
        assertThat(result.fallback()).isFalse();
    }

    @Test
    void missingBranchIsAskedAndAnEntityOnlyReplyContinuesTheCorrectIntent() {
        var first = runtime.evaluate(bot, null, "giờ hoạt động");
        assertThat(first.context().pendingEntity()).isEqualTo("chi_nhanh");
        var reply = runtime.evaluate(bot, first.context(), "Hà Nội");
        assertThat(reply.message()).contains("08:00–21:00");
    }

    @Test
    void collectsThreeSlotsAcrossTurnsAndRendersEachMissingPrompt() {
        var product = runtime.evaluate(bot, null, "báo giá");
        assertThat(product.context().pendingEntity()).isEqualTo("san_pham");
        var quantity = runtime.evaluate(bot, product.context(), "sản phẩm A");
        assertThat(quantity.message()).contains("Sản phẩm A").doesNotContain("{{");
        assertThat(quantity.context().pendingEntity()).isEqualTo("so_luong");
        var area = runtime.evaluate(bot, quantity.context(), "20");
        assertThat(area.message()).contains("20", "Sản phẩm A").doesNotContain("{{");
        var summary = runtime.evaluate(bot, area.context(), "Hà Nội");
        assertThat(summary.message()).contains("20 Sản phẩm A", "Hà Nội", "Bạn xác nhận không?").doesNotContain("{{");
        assertThat(summary.context().pendingEntity()).isEqualTo("xac_nhan");
    }

    @Test
    void allDataInOneMessageSkipsRedundantQuestionsButStillRequiresConfirmation() {
        var result = quote();
        assertThat(result.message()).contains("20 Sản phẩm A", "Hà Nội", "Bạn xác nhận không?");
        assertThat(result.context().variables()).containsEntry("san_pham", "Sản phẩm A")
                .containsEntry("so_luong", "20").containsEntry("chi_nhanh", "Hà Nội");
        assertThat(result.trace().visitedNodes()).hasSize(4);
        assertThat(result.message()).doesNotContain("Bạn đã xác nhận");
    }

    @Test
    void confirmationAndRejectionAreDifferentAndNeitherCreatesAnOrder() {
        var accepted = runtime.evaluate(bot, quote().context(), "xác nhận");
        assertThat(accepted.message()).contains("Bạn đã xác nhận", "chưa tạo đơn", "chưa gửi thông tin");
        var rejected = runtime.evaluate(bot, quote().context(), "không đồng ý");
        assertThat(rejected.message()).contains("Bạn chưa đồng ý", "không tạo đơn").doesNotContain("Bạn đã xác nhận");
    }

    @Test
    void earlyAgreementCannotApproveABriefTheUserHasNotSeen() {
        var result = runtime.evaluate(bot, null, "báo giá 20 sản phẩm A giao Hà Nội, đồng ý");
        assertThat(result.message()).contains("Hãy trả lời ‘xác nhận’ sau khi đọc").doesNotContain("Bạn đã xác nhận");
        var accepted = runtime.evaluate(bot, result.context(), "xác nhận");
        assertThat(accepted.message()).contains("Bạn đã xác nhận").doesNotContain("{{");
    }

    @Test
    void changedQuantityUpdatesThePendingSummaryWithoutLosingOtherSlots() {
        var changed = runtime.evaluate(bot, quote().context(), "đổi thành 30");
        assertThat(changed.message()).contains("30 Sản phẩm A", "Hà Nội").doesNotContain("20 Sản phẩm A");
        assertThat(changed.context().pendingEntity()).isEqualTo("xac_nhan");
        assertThat(runtime.evaluate(bot, changed.context(), "xác nhận").message()).contains("Bạn đã xác nhận");
    }

    @Test
    void productAndAreaCanAlsoBeCorrectedBeforeConfirmation() {
        var changed = runtime.evaluate(bot, quote().context(), "sản phẩm B giao HCM");
        assertThat(changed.message()).contains("20 Sản phẩm B", "HCM").doesNotContain("Hà Nội", "{{");
    }

    @Test
    void unknownQuantityDoesNotBecomeAValidSlot() {
        var first = runtime.evaluate(bot, null, "báo giá sản phẩm A");
        var invalid = runtime.evaluate(bot, first.context(), "35");
        assertThat(invalid.message()).contains("10, 20, 30, 50 hoặc 100");
        assertThat(invalid.context().pendingEntity()).isEqualTo("so_luong");
        assertThat(invalid.context().variables()).doesNotContainKey("so_luong");
    }

    @Test
    void cancelAndNewRequestDoNotDonateFactsFromAPreviousCustomer() {
        var cancelled = runtime.evaluate(bot, quote().context(), "hủy");
        assertThat(cancelled.context().variables()).isEmpty();
        var accepted = runtime.evaluate(bot, quote().context(), "xác nhận");
        var fresh = runtime.evaluate(bot, accepted.context(), "báo giá");
        assertThat(fresh.context().pendingEntity()).isEqualTo("san_pham");
        assertThat(fresh.context().variables()).isEmpty();
    }

    @Test
    void topicChangeDoesNotReuseTheQuoteAreaAsTheNewBranch() {
        var result = runtime.evaluate(bot, quote().context(), "giờ hoạt động");
        assertThat(result.context().pendingEntity()).isEqualTo("chi_nhanh");
        assertThat(result.context().variables()).isEmpty();
        assertThat(result.message()).doesNotContain("08:00–21:00");
    }

    @Test
    void unknownRequestUsesConfiguredFallback() {
        var result = runtime.evaluate(bot, (PreviewContext) null, "thời tiết sao hỏa");
        assertThat(result.fallback()).isTrue();
        assertThat(result.matchedNode()).isEqualTo("06 - Chưa hiểu yêu cầu");
        assertThat(result.message()).contains("Mình chưa hiểu yêu cầu", "Không gọi AI/KB");
    }

    private RuntimeResponse quote() {
        return runtime.evaluate(bot, null, "báo giá 20 sản phẩm A giao Hà Nội");
    }
}
