package com.blueai.rag.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.blueai.rag.chat.service.OutputLanguageAssessment.Result.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputLanguageAssessmentTest {
    private static final ReplyLanguagePolicy.State CHINESE = new ReplyLanguagePolicy.State("zh-CN", false);

    @Test
    void authorizedProductNamesDoNotOutvoteChineseProse() {
        String name = "Bluecom Japan Unlimited Premium International Travel";
        String answer = "请选择" + name + "。该套餐适合您的旅行。";
        assertThat(OutputLanguageAssessment.assess(answer, CHINESE, List.of(name))).isEqualTo(MATCH);
        assertThat(answer).contains(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"可以。", "请开启数据漫游。", "请提供您的设备型号。"})
    void recognizesShortChineseWithoutInputSlotRules(String text) {
        assertThat(OutputLanguageAssessment.assess(text, CHINESE, List.of())).isEqualTo(MATCH);
        assertThat(ReplyLanguagePolicy.isChineseAnswer(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请看 `Please activate your data roaming before travelling.`。",
            "请看 \"Please enable data roaming.\"。",
            "请看 ‘Please contact support.’。",
            "请关闭 `Chế độ máy bay` 后开启数据漫游。",
            "请在 Cài đặt 中选择旅行eSIM。",
            "如果仍无 *** không có mạng ***，请告诉我。",
            "请看 `Hủy yêu cầu`。",
            "请看 `Thông tin thanh toán`。",
            "请看 `data roaming`。"
    })
    void formattingCannotConcealForeignProse(String text) {
        assertThat(OutputLanguageAssessment.assess(text, CHINESE, List.of())).isEqualTo(MISMATCH);
        assertThat(ReplyLanguagePolicy.isChineseAnswer(text)).isFalse();
    }

    @Test
    void modelDeclaredIdentifiersAreNotAnEvidenceRegistry() {
        String text = "请看 {\"identifiers\":[\"Please enable data roaming\"]}。";
        assertThat(OutputLanguageAssessment.assess(text, CHINESE, List.of())).isEqualTo(MISMATCH);
    }

    @Test
    void authorizedChineseNamesCannotMakeEnglishProseChinese() {
        String text = "Please contact 中国移动通信集团北京有限公司 for help.";
        assertThat(OutputLanguageAssessment.assess(text, CHINESE, List.of("中国移动通信集团北京有限公司")))
                .isEqualTo(MISMATCH);
    }

    @Test
    void unclassifiedBrandNamesRemainUncertainAndDoNotThrow() {
        String brand = "Bluecom Japan Unlimited Premium";
        assertThat(OutputLanguageAssessment.assess(brand, CHINESE, List.of())).isEqualTo(UNCERTAIN);
        assertThat(OutputLanguageAssessment.assess(brand, CHINESE, List.of(brand))).isEqualTo(UNCERTAIN);
        assertThatCode(() -> ReplyLanguagePolicy.checkedAnswer("套餐：" + brand + "，30天。", CHINESE))
                .doesNotThrowAnyException();
    }

    @Test
    void verifiedNamesCanRemainInTheirOriginalLanguage() {
        String text = "您好，Nguyễn Văn An！请确认您的目的地是北京。";
        assertThat(OutputLanguageAssessment.assess(text, CHINESE, List.of("Nguyễn Văn An", "北京")))
                .isEqualTo(MATCH);
    }

    @Test
    void englishAndVietnameseAssessmentUsesTheSameOfflineContract() {
        var english = new ReplyLanguagePolicy.State("en", false);
        var vietnamese = new ReplyLanguagePolicy.State("vi", false);
        String en = "Please enable data roaming on your phone.";
        String vi = "Bạn hãy bật chuyển vùng dữ liệu trên điện thoại.";
        assertThat(OutputLanguageAssessment.assess(en, english, List.of())).isEqualTo(MATCH);
        assertThat(OutputLanguageAssessment.assess(vi, vietnamese, List.of())).isEqualTo(MATCH);
        assertThat(OutputLanguageAssessment.assess(en, vietnamese, List.of())).isEqualTo(MISMATCH);
        assertThat(OutputLanguageAssessment.assess(vi, english, List.of())).isEqualTo(MISMATCH);
        // The legacy guard continues to leave VI/EN behavior unchanged.
        assertThat(ReplyLanguagePolicy.checkedAnswer(vi, english)).isEqualTo(vi);
        assertThat(ReplyLanguagePolicy.checkedAnswer(en, vietnamese)).isEqualTo(en);
    }

    @Test
    void checksProseAfterAThousandCharactersInsteadOfOnlyThePrefix() {
        String text = "Please use your phone. ".repeat(60) + "Bạn hãy bật chuyển vùng dữ liệu trên điện thoại.";
        assertThat(OutputLanguageAssessment.assess(text, new ReplyLanguagePolicy.State("en", false), List.of()))
                .isEqualTo(MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"東京で利用できます。", "한국어로 답변해 주세요"})
    void otherSupportedDetectorScriptsAreNotChinese(String text) {
        assertThat(OutputLanguageAssessment.assess(text, CHINESE, List.of())).isEqualTo(MISMATCH);
    }

    @Test
    void identifiersOrEmptyOutputDoNotEstablishLanguage() {
        for (String text : List.of("", "...", "12345", "eSIM APN")) {
            assertThat(OutputLanguageAssessment.assess(text, CHINESE, List.of())).isEqualTo(UNCERTAIN);
        }
        assertThat(OutputLanguageAssessment.assess(null, CHINESE, List.of())).isEqualTo(UNCERTAIN);
        assertThatThrownBy(() -> ReplyLanguagePolicy.checkedAnswer("Please enable data roaming.", CHINESE))
                .isInstanceOf(com.blueai.auth.exception.ApiException.class);
    }
}
