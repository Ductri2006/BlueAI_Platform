package com.blueai.rag.chat.service;

import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import com.blueai.studio.dto.BotStudioDtos.PreviewContext;
import com.blueai.studio.dto.BotStudioDtos.RuntimeResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class BilingualReplyServiceTest {
    private final RagCompletionClient completion = mock(RagCompletionClient.class);
    private final BilingualReplyService replies = new BilingualReplyService(completion, new ObjectMapper());
    private final ReplyLanguagePolicy.State english = new ReplyLanguagePolicy.State("en", false);

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
}
