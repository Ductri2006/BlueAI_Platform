package com.blueai.rag.chat.service;

import com.blueai.rag.chat.dto.RagAnswerResponse;
import com.blueai.rag.chat.dto.RagSourceResponse;
import com.blueai.rag.conversation.dto.ConversationMessageResponse;
import com.blueai.rag.conversation.model.MessageEvidence;
import com.blueai.rag.conversation.model.MessageRole;

import java.util.List;
import java.util.regex.Pattern;

/** Customer chat projection. Retrieval and persisted evidence remain server-side. */
public final class AnswerPresentation {
    private static final Pattern SOURCE_LINK = Pattern.compile(
            "(?i)\\[(\\[S\\d+(?:\\s*[,;]\\s*S?\\d+)*\\]|S\\d+)\\]\\([^\\r\\n)]*\\)");
    private static final Pattern SOURCE_LABEL = Pattern.compile(
            "(?i)\\[S\\d+(?:\\s*[,;]\\s*S?\\d+)*\\]");

    private AnswerPresentation() {
    }

    public static RagAnswerResponse answer(RagAnswerResponse response) {
        return new RagAnswerResponse(response.userMessage(), message(response.assistantMessage()),
                List.of(), response.contextSources());
    }

    public static ConversationMessageResponse message(ConversationMessageResponse response) {
        if (response.role() != MessageRole.ASSISTANT) return response;
        MessageEvidence evidence = response.evidence();
        List<RagSourceResponse> sources = evidence == null ? List.of() : evidence.sources();
        MessageEvidence visibleEvidence = evidence == null ? null
                : new MessageEvidence(List.of(), evidence.contextSources(), evidence.origin());
        return new ConversationMessageResponse(response.id(), response.role(),
                text(response.content(), sources), response.createdAt(), visibleEvidence);
    }

    public static String text(String content) {
        return text(content, List.of());
    }

    public static String text(String content, List<RagSourceResponse> sources) {
        if (content == null || content.isEmpty()) return content;
        String visible = SOURCE_LINK.matcher(content).replaceAll("");
        visible = SOURCE_LABEL.matcher(visible).replaceAll("");
        for (RagSourceResponse source : sources) {
            if (source.filename() != null && !source.filename().isBlank()) {
                visible = visible.replace(source.filename(), "tài liệu nội bộ");
            }
            // Short numeric IDs may be legitimate quantities in the answer.
            for (String identifier : new String[]{source.documentId(), source.chunkId()}) {
                if (identifier != null && identifier.length() >= 8) {
                    visible = visible.replace(identifier, "[ẩn]");
                }
            }
        }
        return visible.replaceAll("[ \\t]+([,.;:!?])", "$1")
                .replaceAll("(?m)[ \\t]+$", "").strip();
    }
}
