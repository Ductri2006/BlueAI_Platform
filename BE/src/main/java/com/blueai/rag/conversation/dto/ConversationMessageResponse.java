package com.blueai.rag.conversation.dto;

import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.rag.conversation.model.MessageEvidence;

import java.time.Instant;

public record ConversationMessageResponse(
        String id, MessageRole role, String content, Instant createdAt, MessageEvidence evidence,
        com.blueai.rag.conversation.model.AnswerStatus answerStatus
) {

    public ConversationMessageResponse {
        answerStatus = answerStatus == null ? com.blueai.rag.conversation.model.AnswerStatus.ANSWERED : answerStatus;
        if (role == MessageRole.ASSISTANT)
            content = com.blueai.studio.dto.PublicAnswerText.toPlainText(content);
    }

    public ConversationMessageResponse(String id, MessageRole role, String content, Instant createdAt, MessageEvidence evidence) {
        this(id, role, content, createdAt, evidence, com.blueai.rag.conversation.model.AnswerStatus.ANSWERED);
    }

    public static ConversationMessageResponse from(ConversationMessage message) {
        return new ConversationMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt(),
                message.getEvidence(), message.getAnswerStatus()
        );
    }
}
