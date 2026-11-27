package com.blueai.rag.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRelevanceRankerTest {

    private final ContextRelevanceRanker ranker = new ContextRelevanceRanker();

    @Test
    void englishFollowUpsKeepContextAndBusinessQuestionsStillRequireEvidence() {
        assertThat(ranker.isFollowUpQuestion("Please tell me more about that plan")).isTrue();
        assertThat(ranker.isContextRecallQuestion("What did I tell you about my phone?")).isTrue();
        assertThat(ranker.isContextRecallQuestion("Remember my trip and compare the refund policy")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Vậy nếu khách dùng máy lock thì sao? Nhắc lại hành trình và số ngày của khách trước khi tư vấn nhé.",
            "Vay neu khach dung may lock thi sao? Nhac lai hanh trinh va so ngay truoc khi tu van.",
            "Nhắc lại hành trình của khách rồi đề xuất gói eSIM phù hợp.",
            "Nhắc lại số ngày, nên chọn gói nào cho khách?",
            "Khách dùng máy lock thì sao? Nhắc lại tên khách trước nhé.",
            "Nhắc lại hành trình đã ghi nhận và khuyến nghị cho khách.",
            "Nhắc lại số lượng từ context, then recommend an eSIM plan.",
            "Based on context, nhắc lại hành trình and advise on compatibility."
    })
    void adviceCombinedWithRecallStillRequiresKnowledge(String question) {
        assertThat(ranker.isContextRecallQuestion(question)).as(question).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Nhắc lại hành trình và số ngày của khách đã ghi nhận.",
            "Tôi đã cung cấp số lượng eSIM Nhật Bản nào?",
            "Nhắc lại tình trạng máy lock mà khách đã cung cấp.",
            "Nhắc lại gói eSIM và thời hạn tôi đã nói trước đó."
    })
    void productAndDeviceFactsAloneRemainContextOnly(String question) {
        assertThat(ranker.isContextRecallQuestion(question)).as(question).isTrue();
    }

    @Test
    void mixedAndPolicyQuestionsNeverSkipKnowledgeRetrieval() {
        for (String question : new String[]{
                "Nhắc lại số lượng Hikari từ context, rồi theo Knowledge Base hướng dẫn xử lý eSIM không có sóng.",
                "Nhắc lại chính sách hoàn tiền đã nói trước đó.",
                "Dựa trên context, hãy so sánh các gói.",
                "Context là gì?"
        }) {
            assertThat(ranker.isContextRecallQuestion(question)).as(question).isFalse();
        }
        assertThat(ranker.isContextRecallQuestion("Tôi đã cung cấp mã cơ hội nào?")).isTrue();
        assertThat(ranker.isContextRecallQuestion("Nhắc lại số lượng mới và hạn báo giá đã ghi nhận.")).isTrue();
    }

    @Test
    void normalizesVietnameseDForRelevance() {
        assertThat(ranker.score("Đà Nẵng", "Da Nang")).isEqualTo(1.0);
    }

    @Test
    void explicitCustomerNameSurvivesVerboseMultilingualRecallQuestion() {
        String question = "Bạn còn nhớ khách QATarvos có bao nhiêu nhân viên sales, ngân sách tháng bao nhiêu "
                + "và mã cơ hội gì không? Chỉ nhắc lại dữ kiện trong lịch sử liên quan của tôi.";
        assertThat(ranker.score(question, "QATarvos has 37 sales, budget 2.9 million VND and opportunity TV-4712."))
                .isGreaterThanOrEqualTo(0.35);
        assertThat(ranker.score(question, "Plant rosemary and lavender in spring."))
                .isZero();
        assertThat(ranker.score(question, "QATarvosOther has a private unrelated opportunity."))
                .isLessThan(0.2);
    }

    @Test
    void genericCustomerReferenceDoesNotGrantAnEntityBonus() {
        assertThat(ranker.score("Khách cần bao nhiêu nhân viên sales và ngân sách tháng?", "Can is an unrelated English verb."))
                .isLessThan(0.2);
        assertThat(ranker.score("Customer needs an unusual specification for tomorrow", "Needs is not a customer name"))
                .isEqualTo(2.0 / 7.0); // Ordinary overlap only, with no named-subject bonus.
    }

    @Test
    void detectsVietnameseFollowUpsWithoutTreatingAStandaloneQuestionAsOne() {
        assertThat(ranker.isFollowUpQuestion("Vậy gói đó có phù hợp không?")).isTrue();
        assertThat(ranker.isFollowUpQuestion("Bạn giải thích lại bước đó giúp mình.")).isTrue();
        assertThat(ranker.isFollowUpQuestion("Quy trình hoàn tiền eSIM là gì?")).isFalse();
    }

    @Test
    void detectsExplicitContextRecallRequests() {
        assertThat(ranker.isContextRecallQuestion(
                "Không nhắc lại dữ kiện trong câu hỏi; chỉ trả lời từ nội dung tôi đã cung cấp."
        )).isTrue();
        assertThat(ranker.isContextRecallQuestion(
                "Dựa trên context đã liên kết, hãy tóm tắt cơ hội sales."
        )).isTrue();
        assertThat(ranker.isContextRecallQuestion(
                "Theo Knowledge Base, cách cấu hình APN là gì?"
        )).isFalse();
    }

    @Test
    void ranksAccentInsensitiveOverlapAboveIrrelevantHistory() {
        double relevant = ranker.score(
                "Khách Trần cần gói Hàn Quốc nào?",
                "Hồ sơ khách Tran: cần gói eSIM Han Quoc năm ngày"
        );
        double irrelevant = ranker.score(
                "Khách Trần cần gói Hàn Quốc nào?",
                "Chính sách đổi trả phụ kiện điện thoại"
        );

        assertThat(relevant).isGreaterThan(0.2);
        assertThat(irrelevant).isZero();
    }
}
