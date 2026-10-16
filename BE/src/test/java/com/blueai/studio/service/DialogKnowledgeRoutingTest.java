package com.blueai.studio.service;

import com.blueai.studio.dto.BotStudioDtos.PreviewContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DialogKnowledgeRoutingTest {
    private final DialogRuntimeService runtime = new DialogRuntimeService();
    private final SalesDialogDemoTemplate.Configuration template = SalesDialogDemoTemplate.load(new ObjectMapper());

    private BotSnapshot bot(boolean core) {
        return new BotSnapshot(3, "bot", "owner", "Routing test", "", 1, "vi-VN", "Asia/Ho_Chi_Minh",
                core, "", null, template.intents(), template.entities(), template.dialogNodes());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "chào bạn , nếu tôi du lịch châu Âu thì có các gói esim nào và tốc độ ra sao ?",
            "du lịch nhật bản thì gói esim nào là tối ưu và có nhiều dung lượng, tốc độ cao ?",
            "esim có sài được cho máy lock không ?",
            "xin chào, eSIM dùng trên máy khóa mạng được không?",
            "hello, tôi muốn tìm thông tin về cài đặt eSIM",
            "eSIM châu Âu có dùng được không, chào bạn?",
            "CHÀO BẠN, TÔI MUỐN HỎI ESIM CHÂU ÂU",
            "chao ban neu toi du lich chau Au thi co goi esim nao"
    })
    void substantiveQuestionIsNotCapturedByAGreeting(String question) {
        var result = runtime.evaluate(bot(true), null, question);
        assertThat(result.fallback()).isTrue();
        assertThat(result.dialogHandled()).isFalse();
        assertThat(result.matchedIntent()).isNull();
        assertThat(result.context().pendingEntity()).isNull();
        assertThat(result.trace().route()).isEqualTo("CORE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"chào bạn", "xin chào", "hello", "CHÀO BẠN!", "chào bạn ạ"})
    void greetingWithoutABusinessQuestionStillUsesDialog(String question) {
        var result = runtime.evaluate(bot(true), null, question);
        assertThat(result.fallback()).isFalse();
        assertThat(result.matchedIntent()).isEqualTo("chao_hoi");
        assertThat(result.trace().route()).isEqualTo("DIALOG");
    }

    @Test
    void greetingDoesNotOverrideAMoreSpecificConfiguredTask() {
        var result = runtime.evaluate(bot(true), null, "chào bạn, tôi cần báo giá");
        assertThat(result.matchedIntent()).isEqualTo("bao_gia");
        assertThat(result.context().pendingEntity()).isEqualTo("san_pham");
    }

    @ParameterizedTest
    @ValueSource(strings = {"giờ hoạt động", "báo giá"})
    void newQuestionLeavesPendingSlotsInsteadOfRepeatingTheDemo(String initial) {
        var pending = runtime.evaluate(bot(true), null, initial);
        assertThat(pending.context().pendingEntity()).isNotNull();
        var switched = runtime.evaluate(bot(true), pending.context(), "Máy lock có dùng được eSIM không?");
        assertThat(switched.trace().route()).isEqualTo("CORE");
        assertThat(switched.context().pendingEntity()).isNull();
        assertThat(switched.context().variables()).isEmpty();
    }

    @Test
    void newKnowledgeQuestionCannotLeaveAnOldConfirmationArmed() {
        var pending = runtime.evaluate(bot(true), null, "báo giá sản phẩm A số lượng 20 Hà Nội");
        assertThat(pending.context().pendingEntity()).isEqualTo("xac_nhan");
        var switched = runtime.evaluate(bot(true), pending.context(), "eSIM châu Âu có tốc độ ra sao?");
        var later = runtime.evaluate(bot(true), switched.context(), "xác nhận");
        assertThat(later.message()).doesNotContain("Bạn đã xác nhận");
        assertThat(later.context().pendingIntent()).isNull();
        assertThat(later.context().variables()).doesNotContainKeys("san_pham", "so_luong", "chi_nhanh");
    }

    @Test
    void validSlotReplyAndUnclearShortReplyKeepPendingContext() {
        var pending = runtime.evaluate(bot(true), null, "giờ hoạt động");
        var unclear = runtime.evaluate(bot(true), pending.context(), "mình chưa rõ");
        assertThat(unclear.context().pendingEntity()).isEqualTo("chi_nhanh");
        assertThat(runtime.evaluate(bot(true), unclear.context(), "Hà Nội").message()).contains("08:00–21:00");
    }

    @Test
    void longReplyContainingValidSlotsStillContinuesTheCurrentTask() {
        var pending = runtime.evaluate(bot(true), null, "báo giá");
        var reply = runtime.evaluate(bot(true), pending.context(), "tôi muốn sản phẩm A số lượng 20 Hà Nội");
        assertThat(reply.context().pendingEntity()).isEqualTo("xac_nhan");
        assertThat(reply.message()).contains("20 Sản phẩm A", "Hà Nội");
    }

    @Test
    void completedGreetingCannotCaptureTheNextKnowledgeQuestion() {
        var greeting = runtime.evaluate(bot(true), null, "chào bạn");
        var next = runtime.evaluate(bot(true), greeting.context(), "esim có sài được cho máy lock không ?");
        assertThat(next.trace().route()).isEqualTo("CORE");
        assertThat(next.context().waitingNode()).isNull();
    }

    @Test
    void dialogOnlyDoesNotRequestAnUnavailableRagMode() {
        var pending = runtime.evaluate(bot(false), null, "báo giá");
        var next = runtime.evaluate(bot(false), pending.context(), "Máy lock có dùng được eSIM không?");
        assertThat(next.context().pendingEntity()).isEqualTo("san_pham");
        assertThat(next.trace().route()).isEqualTo("DIALOG");
    }
}
