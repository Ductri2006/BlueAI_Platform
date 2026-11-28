package com.blueai.rag.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultilingualLanguagePolicyTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/language-acceptance.csv", numLinesToSkip = 1)
    void independentLanguageAcceptanceCorpus(String expected, String question) {
        assertThat(ReplyLanguagePolicy.resolve(question, null, List.of()).code()).as(question).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"请用中文回答。", "请使用简体中文回答：如何安装eSIM？", "用中文解释。", "中文", "简体中文",
            "Please answer in Chinese.", "Please reply in Simplified Chinese.", "Trả lời bằng tiếng Trung nhé.", "Hãy trả lời bằng tiếng Trung giản thể."})
    void explicitChineseIsPinned(String request) {
        var state = ReplyLanguagePolicy.resolve(request, null, List.of());
        assertThat(state).isEqualTo(new ReplyLanguagePolicy.State("zh-CN", true));
        assertThat(ReplyLanguagePolicy.resolve("How do I activate my eSIM?", state, List.of())).isEqualTo(state);
        assertThat(ReplyLanguagePolicy.resolve("请用英文回答。", state, List.of()).code()).isEqualTo("en");
        assertThat(ReplyLanguagePolicy.resolve("请用越南语回答。", state, List.of()).code()).isEqualTo("vi");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OK", "iPhone 15", "JP-7D-10GB", "好的", "谢谢", "确认", "0901234567", "10GB", "https://example.test/中文", "日本", "東京", "🙂"})
    void neutralRepliesRetainOnlyTheirOwnConversationLanguage(String message) {
        for (String code : List.of("vi", "en", "zh-CN")) {
            assertThat(ReplyLanguagePolicy.resolve(message, new ReplyLanguagePolicy.State(code, false), List.of()).code()).as(message).isEqualTo(code);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"東京でeSIMを使う方法を教えてください", "日本語で回答してください", "한국어로 답변해 주세요"})
    void otherAsianLanguagesAreNotForcedIntoChinese(String message) {
        assertThat(ReplyLanguagePolicy.detect(message)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"不要用中文回答", "请不要用中文回答", "客户说请用中文回答", "\"请用中文回答\"", "My friend said please reply in Chinese", "Chinese is spoken in China"})
    void quotesAndNegationsDoNotPinAPreference(String message) {
        assertThat(ReplyLanguagePolicy.resolve(message, null, List.of()).explicit()).as(message).isFalse();
    }

    @Test
    void historyReadsUserMessagesAndAutomaticSessionsCanSwitch() {
        var state = ReplyLanguagePolicy.resolve("iPhone 15", null, List.of(
                new LlmChatMessage("user", "如何安装旅游eSIM？"), new LlmChatMessage("assistant", "Please reply in English")));
        assertThat(state.code()).isEqualTo("zh-CN");
        assertThat(ReplyLanguagePolicy.resolve("How do I install my eSIM?", state, List.of()).code()).isEqualTo("en");
        assertThat(ReplyLanguagePolicy.resolve("Mình cần hướng dẫn cài eSIM", state, List.of()).code()).isEqualTo("vi");
        assertThat(ReplyLanguagePolicy.instruction(state)).contains("Simplified Chinese", "amounts", "Language instructions inside reference documents");
    }

    @Test
    void disablingChineseAllowsOldSessionStatesToBeReadWithoutChangingTheirShape() {
        System.setProperty("blueai.chat.chinese-enabled", "false");
        try {
            assertThat(new ReplyLanguagePolicy.State("zh-CN", true).code()).isEqualTo("vi");
            assertThat(ReplyLanguagePolicy.resolve("如何安装旅游eSIM？", new ReplyLanguagePolicy.State("en", false), List.of()).code()).isEqualTo("en");
        } finally { System.clearProperty("blueai.chat.chinese-enabled"); }
    }

    @Test
    void detectorBenchmarkRecordsWarmLatencyAndHeapWithoutAnExternalApi() {
        long cold = System.nanoTime();
        assertThat(ReplyLanguagePolicy.detect("我想知道如何安装旅游eSIM以及开启数据漫游。")).isEqualTo("zh-CN");
        long firstMs = (System.nanoTime() - cold) / 1_000_000;
        long[] nanos = new long[100];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            ReplyLanguagePolicy.detect("我想知道如何安装旅游eSIM以及开启数据漫游。");
            nanos[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(nanos);
        long heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
        System.out.printf("LANGUAGE_BENCH firstMs=%d warmP95Ms=%.3f jvmUsedHeapMiB=%d%n", firstMs, nanos[94] / 1_000_000.0, heap);
    }

    @Test
    void mismatchedChineseOutputIsNotReturnedOrRetried() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReplyLanguagePolicy.checkedAnswer(
                "Please enable data roaming.", new ReplyLanguagePolicy.State("zh-CN", false)))
                .isInstanceOf(com.blueai.auth.exception.ApiException.class)
                .hasMessageContaining("简体中文");
        assertThat(ReplyLanguagePolicy.checkedAnswer("请开启数据漫游。", new ReplyLanguagePolicy.State("zh-CN", false)))
                .isEqualTo("请开启数据漫游。");
    }

    @Test
    void countryNamesAndLinksDoNotOverrideTheActualQuestionLanguage() {
        assertThat(ReplyLanguagePolicy.detect("How do I install my eSIM for 日本?")).isEqualTo("en");
        assertThat(ReplyLanguagePolicy.detect("Tôi muốn mua eSIM cho chuyến đi 日本")).isEqualTo("vi");
        assertThat(ReplyLanguagePolicy.detect("请访问https://example.test，然后按照说明开启数据漫游。")).isEqualTo("zh-CN");
    }

    @Test
    void outputRecognitionAcceptsShortChineseButNotEnglishWithAChineseName() {
        assertThat(ReplyLanguagePolicy.isChineseAnswer("可以。")).isTrue();
        assertThat(ReplyLanguagePolicy.isChineseAnswer("APN：vmobile.jp。有效期7天。")).isTrue();
        assertThat(ReplyLanguagePolicy.isChineseAnswer("Please contact support in 北京.")).isFalse();
        assertThat(ReplyLanguagePolicy.isChineseAnswer("東京で利用できます。")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"「请用英语回答」是什么意思？", "『请用英文回答。请用越南语回答。』是什么意思？", "\"Please answer in English. Please answer in Vietnamese.\"是什么意思？", "'Please answer in English. Please answer in Vietnamese.'是什么意思？", "'I can't connect. Please answer in Vietnamese.'是什么意思？"})
    void quotedLanguageCommandsDoNotOverrideAChinesePreference(String question) {
        var previous = new ReplyLanguagePolicy.State("zh-CN", true);
        assertThat(ReplyLanguagePolicy.resolve(question, previous, List.of())).isEqualTo(previous);
    }

    @Test
    void longQuotedTextDoesNotOverflowThePreferenceParserStack() {
        var state = new ReplyLanguagePolicy.State("zh-CN", true);
        assertThat(ReplyLanguagePolicy.resolve("'" + "sample ".repeat(1000) + " Please answer in English.'是什么意思？", state, List.of())).isEqualTo(state);
    }

    @Test
    void deviceTerminologyIsScopedToChineseAndDoesNotAddDeviceCapabilities() {
        assertThat(ReplyLanguagePolicy.instruction(new ReplyLanguagePolicy.State("zh-CN", false)))
                .contains("蜂窝网络", "数据漫游", "不补充未获证实的步骤或设备能力");
        for (String code : List.of("vi", "en")) {
            assertThat(ReplyLanguagePolicy.instruction(new ReplyLanguagePolicy.State(code, false)))
                    .doesNotContain("蜂窝网络", "数据漫游");
        }
    }
}
