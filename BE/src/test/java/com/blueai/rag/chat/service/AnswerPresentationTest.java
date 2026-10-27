package com.blueai.rag.chat.service;

import com.blueai.rag.chat.dto.RagAnswerResponse;
import com.blueai.rag.chat.dto.RagSourceResponse;
import com.blueai.rag.conversation.dto.ConversationMessageResponse;
import com.blueai.rag.conversation.model.AnswerOrigin;
import com.blueai.rag.conversation.model.MessageEvidence;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.studio.dto.BotStudioDtos.PublicRuntimeOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerPresentationTest {
    private final RagSourceResponse source = new RagSourceResponse("document-internal-qa", "private-policy.csv",
            "chunk-internal-qa", 0.9, "Hoàn tiền trong 7 ngày.", "USER_DOCUMENT", "READY");

    @Test
    void removesLabelsAndSourceLinksWithoutRemovingBusinessNumbersOrPublicLinks() {
        assertThat(AnswerPresentation.text("**15 GB**, 430.000 VND [S1, S2].\n"
                + "[S2](https://private.test/file) [[S1]](/internal/file)\n"
                + "Chọn mục [1] tại https://support.example.test."))
                .isEqualTo("**15 GB**, 430.000 VND.\n\nChọn mục [1] tại https://support.example.test.");
    }

    @Test
    void freshAndHistoricalProjectionsHideMetadataWithoutMutatingInternalEvidence() throws Exception {
        var evidence = new MessageEvidence(List.of(source), List.of(), AnswerOrigin.CORE);
        var assistant = new ConversationMessageResponse("a", MessageRole.ASSISTANT,
                "Hoàn tiền trong 7 ngày. [S1] private-policy.csv document-internal-qa chunk-internal-qa", null, evidence);
        var user = new ConversationMessageResponse("u", MessageRole.USER, "Giải thích [S1] private-policy.csv", null, null);
        var visible = AnswerPresentation.answer(new RagAnswerResponse(user, assistant, List.of(source), List.of()));
        String json = new ObjectMapper().writeValueAsString(visible.assistantMessage());
        assertThat(visible.sources()).isEmpty();
        assertThat(visible.userMessage()).isSameAs(user);
        assertThat(AnswerPresentation.message(user)).isSameAs(user);
        assertThat(json).contains("Hoàn tiền trong 7 ngày.", "CORE")
                .doesNotContain("private-policy.csv", "document-internal-qa", "chunk-internal-qa", "[S1]", "excerpt");
        assertThat(assistant.evidence().sources()).containsExactly(source);
        assertThat(assistant.content()).contains("[S1]");
        assertThat(AnswerPresentation.message(assistant)).isEqualTo(visible.assistantMessage());
    }

    @Test
    void legacyHistoryAndCachedPublicRepliesCannotRestoreSourceLabels() throws Exception {
        var legacy = new ConversationMessageResponse("legacy", MessageRole.ASSISTANT, "Thông tin [S1].", null, null);
        assertThat(AnswerPresentation.message(legacy).content()).isEqualTo("Thông tin.");
        var cached = new ObjectMapper().readValue("{\"message\":\"Hoàn tiền [S1].\",\"fallback\":false}", PublicRuntimeOutput.class);
        assertThat(cached.message()).isEqualTo("Hoàn tiền.");
        assertThat(AnswerPresentation.text(null)).isNull();
    }
}
