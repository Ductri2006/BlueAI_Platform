package com.blueai.rag.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyLanguagePolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"Hello, I can't set up my eSIM.", "My eSIM is not working", "How do I install my eSIM?",
            "Can I use this plan in Japan?", "What is the refund policy?", "Please help me activate this plan",
            "Why is my data not working?", "hello", "How much data does this plan include?",
            "I have no internet", "No network", "My phone is locked", "Setup eSIM"})
    void recognizesEnglishSupportQuestions(String message) {
        assertThat(ReplyLanguagePolicy.resolve(message, null, List.of()).code()).isEqualTo("en");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Xin chào", "Mình không cài được eSIM", "toi can huong dan cai esim", "Gói nào dùng được tại Nhật?",
            "Hướng dẫn bật chuyển vùng dữ liệu", "Tôi muốn hỏi chính sách hoàn tiền", "Chao ban toi muon cai esim"})
    void recognizesVietnameseWithAndWithoutAccents(String message) {
        assertThat(ReplyLanguagePolicy.resolve(message, null, List.of()).code()).isEqualTo("vi");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OK", "yes", "no", "iPhone 15", "0901234567", "JP-7D-10GB", "vmobile.jp", "7"})
    void neutralSlotsKeepConversationLanguage(String message) {
        assertThat(ReplyLanguagePolicy.resolve(message, new ReplyLanguagePolicy.State("en", false), List.of()).code()).isEqualTo("en");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Please answer in English.", "Could you please reply in English?", "Trả lời bằng tiếng Anh nhé.",
            "English please", "Please switch to English"})
    void explicitEnglishPreferenceIsRemembered(String message) {
        var state = ReplyLanguagePolicy.resolve(message, null, List.of());
        assertThat(state).isEqualTo(new ReplyLanguagePolicy.State("en", true));
        assertThat(ReplyLanguagePolicy.resolve("Tôi muốn cài eSIM", state, List.of())).isEqualTo(state);
        assertThat(ReplyLanguagePolicy.resolve("Please explain in Vietnamese.", state, List.of()))
                .isEqualTo(new ReplyLanguagePolicy.State("vi", true));
    }

    @Test
    void automaticConversationCanSwitchAndIgnoresAssistantLanguage() {
        var english = ReplyLanguagePolicy.resolve("How do I install my eSIM?", null, List.of());
        assertThat(ReplyLanguagePolicy.resolve("Mình cần hướng dẫn bật chuyển vùng", english, List.of()).code()).isEqualTo("vi");
        assertThat(ReplyLanguagePolicy.resolve("OK", null, List.of(
                new LlmChatMessage("user", "How do I install my eSIM?"),
                new LlmChatMessage("assistant", "Xin chào, tôi có thể giúp gì cho bạn?"))).code()).isEqualTo("en");
    }

    @Test
    void descriptionsAndNegationsDoNotBecomeExplicitPreferences() {
        for (String message : List.of("My friend said please reply in English", "Do not reply in English", "English is spoken in many countries")) {
            assertThat(ReplyLanguagePolicy.resolve(message, null, List.of()).explicit()).isFalse();
        }
    }
}
