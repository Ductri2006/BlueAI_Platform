package com.blueai.studio.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PublicAnswerTextTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Cài đặt **[S4]**. Wi-Fi ổn định [S2, S4].|Cài đặt. Wi-Fi ổn định.",
            "Hướng dẫn [s2,4] và [S3] lưu ý.|Hướng dẫn và lưu ý.",
            "Có eSIM [ S1 , S2 , 4 ]!|Có eSIM!",
            "Mã [Samsung S4], [1, 2], [SKU-4].|Mã [Samsung S4], [1, 2], [SKU-4]."
    })
    void removesOnlyBlueAiMarkers(String text, String expected) {
        assertThat(PublicAnswerText.withoutSources(text)).isEqualTo(expected);
    }

    @Test void keepsParagraphsAndMarkdownIndentation() {
        String text = "**Cài đặt > Thêm eSIM** [S4].\n\n- Mục một\n    - Mục con [S2, S4]\n\n```java\n  call();\n```";
        assertThat(PublicAnswerText.withoutSources(text)).isEqualTo(
                "**Cài đặt > Thêm eSIM**.\n\n- Mục một\n    - Mục con\n\n```java\n  call();\n```");
    }

    @Test void cleansLegacyCachedJsonWhileKeepingInternalBlueAiResponseIntact() throws Exception {
        var internal = new BotStudioDtos.RuntimeResponse("Trả lời [S2, S4].", null, Map.of(), null, null, false);
        assertThat(BotStudioDtos.PublicRuntimeOutput.from(internal).message()).isEqualTo("Trả lời.");
        assertThat(internal.message()).isEqualTo("Trả lời.");
        var cached = new ObjectMapper().readValue("{\"message\":\"Trả lời **[S4]**.\",\"fallback\":false}", BotStudioDtos.PublicRuntimeOutput.class);
        assertThat(cached.message()).isEqualTo("Trả lời.");
        assertThat(PublicAnswerText.withoutSources(null)).isNull();
    }

    @Test void convertsProductMarkdownAndGroupedCitationsForMessagingApps() {
        String text = "## Gói Nhật Bản\n\n1. **Japan (Total 3GB)-10 days**\n   - **SKU:** `BLU-ESIM-04-JAPAN-10D-3072-7CEB176E` [S2]\n   - **Giá:** 95.000 VND [s1,s2,3].\n\n*(Giá sandbox)*";
        assertThat(PublicAnswerText.toPlainText(text)).isEqualTo(
                "Gói Nhật Bản\n\n1. Japan (Total 3GB)-10 days\n   - SKU: BLU-ESIM-04-JAPAN-10D-3072-7CEB176E\n   - Giá: 95.000 VND.\n\n(Giá sandbox)");
    }

    @Test void convertsHtmlEntitiesLinksAndKeepsUsefulText() {
        String text = "<p><strong>Nhật Bản</strong> &amp; Hàn Quốc<br>Giá &lt; 200k</p><script>ignore()</script>\n[Hướng dẫn](https://example.com/guide?a=1&b=2)\n<https://example.com>";
        assertThat(PublicAnswerText.toPlainText(text)).isEqualTo(
                "Nhật Bản & Hàn Quốc\nGiá < 200k\n\nHướng dẫn (https://example.com/guide?a=1&b=2)\nhttps://example.com");
        assertThat(PublicAnswerText.toPlainText("&lt;b&gt;Xin chào&lt;/b&gt; &nbsp;[S1]")).isEqualTo("Xin chào");
    }

    @Test void convertsTablesToLabelledTextAndPreservesIdentifiersAndMath() {
        String text = "| Gói | Giá |\n| --- | ---: |\n| Japan | 95.000 VND |\n| Korea | 86.000 VND |\n\nMã BLU_ESIM_JP, [Global], 2 * 3 = 6, tốc độ 128kbps.\n```text\nXin chào\n```";
        String clean = PublicAnswerText.toPlainText(text);
        assertThat(clean).isEqualTo("Gói: Japan; Giá: 95.000 VND\nGói: Korea; Giá: 86.000 VND\n\nMã BLU_ESIM_JP, [Global], 2 * 3 = 6, tốc độ 128kbps.\n\nXin chào");
        assertThat(PublicAnswerText.toPlainText(clean)).isEqualTo(clean);
    }

    @Test void chatDtoCleansHistoricalAssistantMessagesAndPreservesUserInputAndEvidence() throws Exception {
        var evidence = new com.blueai.rag.conversation.model.MessageEvidence(
                java.util.List.of(),java.util.List.of(),com.blueai.rag.conversation.model.AnswerOrigin.CORE);
        var assistant = new com.blueai.rag.conversation.dto.ConversationMessageResponse("a",
                com.blueai.rag.conversation.model.MessageRole.ASSISTANT,"**Japan** [S1]",java.time.Instant.now(),evidence);
        var user = new com.blueai.rag.conversation.dto.ConversationMessageResponse("u",
                com.blueai.rag.conversation.model.MessageRole.USER,"**literal** [S1]",java.time.Instant.now(),null);
        assertThat(assistant.content()).isEqualTo("Japan");
        assertThat(assistant.evidence()).isSameAs(evidence);
        assertThat(user.content()).isEqualTo("**literal** [S1]");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(assistant);
        assertThat(json).contains("\"content\":\"Japan\"").doesNotContain("[S1]","**");
    }
}
