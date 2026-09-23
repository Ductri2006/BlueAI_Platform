package com.blueai.rag.conversation.model;

import com.blueai.rag.chat.dto.ConversationContextSourceResponse;
import com.blueai.rag.chat.dto.RagSourceResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageEvidenceConverterTest {
    private final MessageEvidenceConverter converter = new MessageEvidenceConverter();

    @Test
    void roundTripsUnicodeEvidenceAndKeepsLegacyRowsReadable() {
        MessageEvidence evidence = new MessageEvidence(
                List.of(new RagSourceResponse("doc", "Kiến thức.csv", "chunk", 0.8,
                        "Bật chuyển vùng dữ liệu", "ENTERPRISE", "APPROVED")),
                List.of(new ConversationContextSourceResponse("USER_MEMORY", null, "Ghi nhớ", "style")));
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(evidence))).isEqualTo(evidence);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void rejectsMalformedEvidenceWithoutLeakingItsContents() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("private broken evidence"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored message evidence is invalid.");
    }

    @Test
    void userMessagesCannotCarryTrustedEvidence() {
        ConversationMessage message = new ConversationMessage(null, MessageRole.USER, "claim",
                new MessageEvidence(List.of(), List.of()));
        assertThat(message.getEvidence()).isNull();
    }
}
