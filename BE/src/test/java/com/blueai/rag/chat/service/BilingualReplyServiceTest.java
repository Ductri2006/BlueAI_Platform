package com.blueai.rag.chat.service;

import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import com.blueai.studio.dto.BotStudioDtos.PreviewContext;
import com.blueai.studio.dto.BotStudioDtos.RuntimeResponse;
import com.blueai.studio.dto.BotStudioDtos.DialogNodeInput;
import com.blueai.studio.dto.BotStudioDtos.DialogReplyInput;
import com.blueai.studio.model.DialogNodeType;
import com.blueai.studio.model.DialogNextStep;
import com.blueai.studio.model.DialogContextReusePolicy;
import com.blueai.studio.service.BotSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class BilingualReplyServiceTest {
    private final RagCompletionClient completion = mock(RagCompletionClient.class);
    private final BilingualReplyService replies = new BilingualReplyService(completion, new ObjectMapper());
    private final ReplyLanguagePolicy.State english = new ReplyLanguagePolicy.State("en", false);
    private final ReplyLanguagePolicy.State chinese = new ReplyLanguagePolicy.State("zh-CN", false);

    @Test
    void builtInFallbackAndSameLanguageNeedNoProviderCall() {
        assertThat(replies.localize("Mình chưa tìm thấy đủ thông tin trong kho tri thức để trả lời chắc chắn.", english))
                .startsWith("I do not have enough verified information");
        assertThat(replies.localize("Xin chào! Mình có thể giúp gì cho bạn?", new ReplyLanguagePolicy.State("vi", false))).startsWith("Xin chào");
        verifyNoInteractions(completion);
    }

    @Test
    void disablingCorePreventsAutomaticDialogTranslation() {
        assertThat(replies.localize("Bạn muốn hỏi về gói eSIM nào?", english, false)).contains("support agent");
        assertThat(replies.localize("Please provide your device model.", new ReplyLanguagePolicy.State("vi", false), false))
                .contains("tiếng Việt");
        verifyNoInteractions(completion);
    }

    @Test
    void configuredEnglishWithVietnameseNamesKeepsEntitiesWithoutCallingAi() {
        var context = new PreviewContext(null, null, null, Map.of("location", "Hà Nội", "customer", "Minh"));
        var result = new RuntimeResponse("Hello Minh! Your location is Hà Nội.", null, Map.of(), "confirmed", context, false);
        assertThat(replies.dialogReply(result, english, false).message()).isEqualTo(result.message());
        verifyNoInteractions(completion);
    }

    @Test
    void automaticDialogTranslationMustKeepRenderedEntityValuesExactly() {
        var context = new PreviewContext(null, null, null, Map.of("location", "Hà Nội"));
        var result = new RuntimeResponse("Bạn đã chọn địa điểm Hà Nội.", null, Map.of(), "confirmed", context, false);
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"Your location is Hanoi.\"}");
        assertThatThrownBy(() -> replies.dialogReply(result, english, true)).isInstanceOf(ApiException.class);
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"Your location is Hà Nội.\"}");
        assertThat(replies.dialogReply(result, english, true).message()).isEqualTo("Your location is Hà Nội.");
    }

    @Test
    void translatesLegacyDialogUsingSharedMeteredClientAndUntrustedJsonInput() {
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"Please set your APN to vmobile.jp. Your plan includes 10 GB for 7 days.\"}");
        String result = replies.localize("Bạn hãy đặt APN là vmobile.jp. Gói của bạn có 10 GB dùng trong 7 ngày.", english);
        assertThat(result).contains("vmobile.jp", "10 GB", "7 days");
        verify(completion).complete(argThat(messages -> messages.size() == 2 && messages.getFirst().content().contains("untrusted data")
                && messages.getLast().content().startsWith("{\"text\":")));
    }

    @Test
    void rejectsChangedNumbersAndApnAndMalformedTranslations() {
        for (String translation : List.of(
                "{\"translation\":\"Please use vmobile.jp with 20 GB for 7 days.\"}",
                "{\"translation\":\"Please use other.jp with 10 GB for 7 days.\"}",
                "{\"translation\":\"Please use vmobile.jp with 10 MB for 7 days.\"}",
                "{\"translation\":\"Please use vmobile.jp with 10 GB for 7 months.\"}",
                "{\"translation\":\"Hãy dùng vmobile.jp với 10 GB trong 7 ngày.\"}",
                "{\"translation\":\"Please use vmobile.jp with 10 GB for 7 days.\",\"extra\":true}", "not json")) {
            when(completion.complete(anyList())).thenReturn(translation);
            assertThatThrownBy(() -> replies.localize("Bạn hãy dùng vmobile.jp với 10 GB trong 7 ngày.", english)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void chineseBuiltInsAndSameLanguageNeedNoProviderCallEvenWithCoreDisabled() {
        Map<String, String> builtIns = Map.of(
                "Xin chào! Bạn muốn tra cứu thông tin gì?", "您好！您想查询什么信息？",
                "Xin chào! Mình có thể giúp gì cho bạn?", "您好！有什么可以帮您？",
                "Đã hủy yêu cầu hiện tại. Bạn muốn mình hỗ trợ việc gì tiếp theo?", "已取消当前请求。接下来有什么可以帮您？",
                "Mình chưa hiểu yêu cầu này. Bạn thử diễn đạt theo cách khác nhé.", "我还没有理解您的请求，请换一种方式描述。",
                "Không có nhánh hội thoại phù hợp để tiếp tục.", "我需要更多信息才能继续，请描述您的需求。",
                "Mình chưa tìm thấy đủ thông tin trong kho tri thức để trả lời chắc chắn.", "知识库中暂时没有足够的可靠信息来准确回答，请提供更多详细信息。");
        builtIns.forEach((source, translated) -> assertThat(replies.localize(source, chinese, false)).isEqualTo(translated));
        String configured = "请检查您的 eSIM 设置，并将 APN 设置为 vmobile.jp。";
        assertThat(replies.localize(configured, chinese, false)).isEqualTo(configured);
        assertThat(replies.localize(configured, chinese)).isEqualTo(configured);
        verifyNoInteractions(completion);
    }

    @Test
    void missingChineseTemplateWithCoreDisabledReturnsSafeChineseSupportFallback() {
        for (String message : List.of("Bạn muốn hỏi về gói eSIM nào?", "Please provide your device model.", "eSIM APN")) {
            var original = new RuntimeResponse(message, "setup", Map.of("device", "iPhone"), "setup",
                    new PreviewContext(null, null, null, Map.of()), false, true);
            RuntimeResponse localized = replies.dialogReply(original, chinese, false);
            assertThat(localized.message()).isEqualTo("此请求需要人工客服协助，请联系客服以继续使用简体中文沟通。");
            assertThat(localized.context().replyLanguage()).isEqualTo("zh-CN");
            assertThat(localized.matchedIntent()).isEqualTo(original.matchedIntent());
            assertThat(localized.recognizedEntities()).isEqualTo(original.recognizedEntities());
            assertThat(localized.dialogHandled()).isTrue();
            assertThat(localized.fallback()).isFalse();
        }
        verifyNoInteractions(completion);
    }

    @Test
    void configuredChineseSnapshotTemplatesWithEnglishTermsAndEntitiesReturnDirectlyWithoutAi() {
        var translations = new DialogReplyInput(
                "您好，{customer}！请将 eSIM 的 APN 设置为 vmobile.jp，您选择的位置是{location}。",
                "请提供您的 eSIM 设备型号和 APN。",
                "请先确认您的 eSIM 和 APN 设置。再提供更多信息。");
        var node = new DialogNodeInput("setup", DialogNodeType.ROUTE, null, "setup", null, null, "device",
                "Bạn hãy đặt APN là vmobile.jp.", "Bạn hãy cung cấp mẫu điện thoại.", DialogNextStep.WAIT_FOR_REPLY,
                null, "Bạn hãy xác nhận cài đặt.", 1, DialogContextReusePolicy.NONE,
                null, null, null, Map.of("zh-CN", translations));
        var snapshot = new BotSnapshot(1, "bot", "owner", "Bot", "", 1, "vi", "Asia/Ho_Chi_Minh", false,
                "", null, List.of(), List.of(), List.of(node));
        var localized = replies.dialogSnapshot(snapshot, chinese);
        var translatedNode = localized.dialogNodes().getFirst();
        assertThat(translatedNode.responseText()).isEqualTo(translations.responseText());
        assertThat(translatedNode.missingEntityPrompt()).isEqualTo(translations.missingEntityPrompt());
        assertThat(translatedNode.irrelevantResponseText()).isEqualTo(translations.irrelevantResponseText());
        assertThat(translatedNode.matchIntent()).isEqualTo(node.matchIntent());
        assertThat(translatedNode.requiredEntityName()).isEqualTo(node.requiredEntityName());
        assertThat(translatedNode.nextStep()).isEqualTo(node.nextStep());
        assertThat(snapshot.dialogNodes().getFirst()).isSameAs(node);
        var context = new PreviewContext(null, null, null, Map.of("customer", "Minh", "location", "Hà Nội"));
        for (String template : List.of(translatedNode.responseText(), translatedNode.missingEntityPrompt(), translatedNode.irrelevantResponseText())) {
            String rendered = template.replace("{customer}", "Minh").replace("{location}", "Hà Nội");
            var result = new RuntimeResponse(rendered, "setup", Map.of(), "setup", context, false);
            assertThat(replies.dialogReply(result, chinese, false).message()).isEqualTo(rendered);
        }
        assertThat(replies.dialogSnapshot(snapshot, new ReplyLanguagePolicy.State("vi", false))).isSameAs(snapshot);
        verifyNoInteractions(completion);
    }

    @Test
    void translatesIntoSimplifiedChineseThroughSharedClientAndPreservesAdjacentProtectedValues() {
        String source = "Bạn hãy đặt APN là vmobile.jp cho eSIM, mã PLAN-7. Gói có 10 GB trong 7 ngày, giá 20 USD.";
        String translated = "请将eSIM的APN设置为vmobile.jp，编号PLAN-7。套餐含10GB，有效期为7天，价格为20USD。";
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + translated + "\"}");
        assertThat(replies.localize(source, chinese)).isEqualTo(translated);
        verify(completion).complete(argThat(messages -> messages.size() == 2
                && messages.getFirst().content().contains("Simplified Chinese")
                && messages.getFirst().content().contains("untrusted data")
                && messages.getFirst().content().contains("APNs")
                && messages.getLast().content().startsWith("{\"text\":")
                && messages.getLast().content().contains("PLAN-7")));
    }

    @Test
    void chineseTimeUnitsCanBeTranslatedWithoutChangingMeaningInEitherDirection() {
        String vietnamese = "Gói của bạn có 10 GB trong 7 ngày, 24 giờ, 2 tuần và 3 tháng.";
        String translated = "您的套餐包含10GB，可用7天、24小时、2周和3个月。";
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + translated + "\"}");
        assertThat(replies.localize(vietnamese, chinese)).isEqualTo(translated);
        String englishText = "Your plan includes 10 GB for 7 days, 24 hours, 2 weeks and 3 months.";
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + englishText + "\"}");
        assertThat(replies.localize(translated, english)).isEqualTo(englishText);
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + vietnamese + "\"}");
        assertThat(replies.localize(translated, new ReplyLanguagePolicy.State("vi", false))).isEqualTo(vietnamese);
    }

    @Test
    void chinesePunctuationAndAdjacentAmountsDoNotChangeProtectedUrlsIdentifiersOrCode() {
        String source = "Bạn hãy xem https://example.com/help hoặc gửi email tới support@example.com, dùng mã `ABC-123`. Giá là USD20 cho iPhone15.";
        String translated = "请访问https://example.com/help，或发送邮件至support@example.com，使用代码`ABC-123`。iPhone15的价格为20USD。";
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + translated + "\"}");
        assertThat(replies.localize(source, chinese)).isEqualTo(translated);
        for (String invalid : List.of(translated.replace("example.com/help", "example.com/other"),
                translated.replace("support@example.com", "other@example.com"), translated.replace("ABC-123", "ABC-124"),
                translated.replace("iPhone15", "iPhone16"))) {
            when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + invalid + "\"}");
            assertThatThrownBy(() -> replies.localize(source, chinese)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void configuredChineseReplyWithAdjacentUrlKeepsProseAndNeverCallsAi() {
        String configured = "请访问https://example.com/help，或发送邮件至support@example.com，使用代码`ABC-123`。iPhone15的价格为20USD。";
        assertThat(replies.localize(configured, chinese, false)).isEqualTo(configured);
        assertThat(replies.localize(configured, chinese, true)).isEqualTo(configured);
        verifyNoInteractions(completion);
    }

    @Test
    void matchingProtectedValuesWithoutChineseProseStillFailsLanguageValidation() {
        String source = "Bạn hãy xem https://example.com/help hoặc gửi email tới support@example.com, dùng mã `ABC-123`. Giá là USD20 cho iPhone15.";
        String identifiersOnly = "https://example.com/help support@example.com `ABC-123` iPhone15 20USD";
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + identifiersOnly + "\"}");
        assertThatThrownBy(() -> replies.localize(source, chinese))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("REPLY_TRANSLATION_FAILED"));
    }

    @Test
    void chineseAdjacentSourceQuantitiesCannotChangeUnitsWhenTranslatedBackToEnglish() {
        String source = "您的套餐包含10GB，可用7天、24小时、2周和3个月。";
        for (String translated : List.of(
                "Your plan includes 10 GB for 7 months, 24 hours, 2 weeks and 3 months.",
                "Your plan includes 10 GB for 7 days, 24 days, 2 weeks and 3 months.",
                "Your plan includes 10 GB for 7 days, 24 hours, 2 days and 3 months.",
                "Your plan includes 10 GB for 7 days, 24 hours, 2 weeks and 3 days.")) {
            when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + translated + "\"}");
            assertThatThrownBy(() -> replies.localize(source, english)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void rejectsChineseTranslationsWithWrongUnitsEvenWhenEveryNumberIsUnchanged() {
        String source = "Gói của bạn có 10 GB trong 7 ngày, 24 giờ, 2 tuần và 3 tháng.";
        for (String translated : List.of(
                "您的套餐包含10GB，可用7个月、24小时、2周和3个月。",
                "您的套餐包含10GB，可用7天、24天、2周和3个月。",
                "您的套餐包含10GB，可用7天、24小时、2天和3个月。",
                "您的套餐包含10GB，可用7天、24小时、2周和3天。",
                "您的套餐包含10MB，可用7天、24小时、2周和3个月。")) {
            when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + translated + "\"}");
            assertThatThrownBy(() -> replies.localize(source, chinese)).isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getCode()).isEqualTo("REPLY_TRANSLATION_FAILED");
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(exception.getMessage()).isEqualTo("暂时无法安全地翻译此回复，请重试或联系人工客服。");
            });
        }
    }

    @Test
    void chineseAdjacentIdentifiersCurrenciesAndNumbersCannotBeChangedOrDuplicated() {
        String source = "Bạn hãy đặt APN là vmobile.jp cho eSIM, mã PLAN-7. Gói có 10 GB trong 7 ngày, giá 20 USD.";
        for (String translated : List.of(
                "请将eSIM的APN设置为other.jp，编号PLAN-7。套餐含10GB，有效期为7天，价格为20USD。",
                "请将eSIM的APN设置为vmobile.jp，编号PLAN-8。套餐含10GB，有效期为7天，价格为20USD。",
                "请将eSIM的APN设置为vmobile.jp，编号PLAN-7。套餐含10GB，有效期为7天，价格为20CNY。",
                "请将eSIM的APN设置为vmobile.jp，编号PLAN-7。套餐含10GB，有效期为7天，价格为30USD。",
                "请将SIM的APN设置为vmobile.jp，编号PLAN-7。套餐含10GB，有效期为7天，价格为20USD。",
                "请将eSIM的接入点设置为vmobile.jp，编号PLAN-7。套餐含10GB，有效期为7天，价格为20USD。",
                "请将eSIM的APN设置为vmobile.jp，编号PLAN-7。套餐含10GB，有效期为7天，价格为20USD及20USD。")) {
            when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + translated + "\"}");
            assertThatThrownBy(() -> replies.localize(source, chinese)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void chineseDialogTranslationMustPreserveRenderedEntitiesAndOccurrenceCounts() {
        var context = new PreviewContext(null, null, null, Map.of("location", "Hà Nội", "customer", "Minh"));
        var original = new RuntimeResponse("Bạn Minh đã chọn địa điểm Hà Nội.", "location", Map.of(), "confirmed", context, false);
        for (String translated : List.of("您好Minh，您选择的位置是河内。", "您好Minh，您选择的位置是Hà Nội。Hà Nội。", "您好明，您选择的位置是Hà Nội。")) {
            when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + translated + "\"}");
            assertThatThrownBy(() -> replies.dialogReply(original, chinese, true)).isInstanceOf(ApiException.class);
        }
        String valid = "您好Minh，您选择的位置是Hà Nội。";
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + valid + "\"}");
        assertThat(replies.dialogReply(original, chinese, true).message()).isEqualTo(valid);
    }

    @Test
    void rejectsMalformedAndUnknownOrWrongLanguageChineseAiOutput() {
        for (String response : List.of(
                "not json", "null", "[]", "{}", "{\"translation\":null}", "{\"translation\":42}", "{\"translation\":\"\"}",
                "{\"translation\":\"请提供您的设备型号。\",\"extra\":true}",
                "{\"translation\":\"请提供您的设备型号。\"} {}",
                "{\"translation\":\"请提供您的设备型号。\",\"translation\":\"请提供您的设备型号。\"}",
                "{\"translation\":\"Please provide your device model.\"}",
                "{\"translation\":\"Bạn hãy cung cấp mẫu điện thoại.\"}",
                "{\"translation\":\"OK\"}", "{\"translation\":\"...\"}",
                "{\"translation\":\"" + "请".repeat(12_001) + "\"}")) {
            when(completion.complete(anyList())).thenReturn(response);
            assertThatThrownBy(() -> replies.localize("Bạn hãy cung cấp mẫu điện thoại.", chinese)).isInstanceOf(ApiException.class);
        }
        when(completion.complete(anyList())).thenReturn(null);
        assertThatThrownBy(() -> replies.localize("Bạn hãy cung cấp mẫu điện thoại.", chinese)).isInstanceOf(ApiException.class);
    }

    @Test
    void translationKeepsQuotaFailureAndRetryMetadataFromSharedClient() {
        var quota = new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOKEN_QUOTA_EXHAUSTED", "Đã hết hạn mức AI.", 73L);
        when(completion.complete(anyList())).thenThrow(quota);
        assertThatThrownBy(() -> replies.localize("Bạn hãy cung cấp mẫu điện thoại.", chinese)).isSameAs(quota);
        verify(completion, times(1)).complete(anyList());
    }

    @Test
    void configuredShortChineseRepliesAndTechnicalLabelsNeedNoAi() {
        for (String configured : List.of("可以。", "请开启数据漫游。", "APN：vmobile.jp。有效期7天。")) {
            var original = new RuntimeResponse(configured, "setup", Map.of(), "configured",
                    new PreviewContext(null, null, null, Map.of()), false, true);
            assertThat(replies.dialogReply(original, chinese, false).message()).isEqualTo(configured);
            assertThat(replies.dialogReply(original, chinese, true).message()).isEqualTo(configured);
        }
        verifyNoInteractions(completion);
    }

    @Test
    void shortChineseAiTranslationsUseOutputValidationAndKeepApnAndValidity() {
        Map<String, String> translations = Map.of(
                "Bạn có thể tiếp tục.", "可以。",
                "Bạn hãy bật chuyển vùng dữ liệu.", "请开启数据漫游。",
                "APN là vmobile.jp. Có hiệu lực 7 ngày.", "APN：vmobile.jp。有效期7天。");
        translations.forEach((source, translated) -> {
            when(completion.complete(anyList())).thenReturn("{\"translation\":\"" + translated + "\"}");
            assertThat(replies.localize(source, chinese)).isEqualTo(translated);
        });
        verify(completion, times(3)).complete(anyList());
    }

    @Test
    void englishProseWithChineseProperNamesIsNotAnAlreadyLocalizedReply() {
        for (String message : List.of("Your destination is 北京. Please enable data roaming.",
                "Please contact 中国移动通信集团北京有限公司 for help.")) {
            assertThat(replies.localize(message, chinese, false))
                    .isEqualTo("此请求需要人工客服协助，请联系客服以继续使用简体中文沟通。");
        }
        verifyNoInteractions(completion);
    }

    @Test
    void englishAiOutputWithPreservedChineseProperNameStillFailsOutputValidation() {
        var context = new PreviewContext(null, null, null, Map.of("location", "北京"));
        var original = new RuntimeResponse("Bạn đã chọn địa điểm 北京.", "location", Map.of(), "confirmed", context, false);
        when(completion.complete(anyList())).thenReturn("{\"translation\":\"Your destination is 北京. Please enable data roaming.\"}");
        assertThatThrownBy(() -> replies.dialogReply(original, chinese, true))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("REPLY_TRANSLATION_FAILED"));
    }
}
