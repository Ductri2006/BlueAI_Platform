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
        assertThat(internal.message()).isEqualTo("Trả lời [S2, S4].");
        var cached = new ObjectMapper().readValue("{\"message\":\"Trả lời **[S4]**.\",\"fallback\":false}", BotStudioDtos.PublicRuntimeOutput.class);
        assertThat(cached.message()).isEqualTo("Trả lời.");
        assertThat(PublicAnswerText.withoutSources(null)).isNull();
    }
}
