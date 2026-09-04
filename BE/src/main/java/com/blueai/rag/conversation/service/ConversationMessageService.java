package com.blueai.rag.conversation.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.rag.conversation.dto.ConversationMessageResponse;
import com.blueai.rag.conversation.dto.CreateUserMessageRequest;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class ConversationMessageService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;

    public ConversationMessageService(
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public ConversationMessageResponse addUserMessage(
            String ownerId,
            String conversationId,
            CreateUserMessageRequest request
    ) {
        Conversation conversation = findOwnedConversation(ownerId, conversationId);
        ConversationMessage message = messageRepository.saveAndFlush(new ConversationMessage(
                conversation,
                MessageRole.USER,
                request.content().trim()
        ));
        conversation.touch();
        return ConversationMessageResponse.from(message);
    }

    @Transactional(readOnly = true)
    public List<ConversationMessageResponse> listRecentMessages(String ownerId, String conversationId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_LIMIT", "limit must be between 1 and 100.");
        }
        findOwnedConversation(ownerId, conversationId);

        List<ConversationMessageResponse> messages = new ArrayList<>(messageRepository
                .findByConversation_IdOrderByCreatedAtDescIdDesc(conversationId, PageRequest.of(0, limit))
                .stream()
                .map(ConversationMessageResponse::from)
                .toList());
        Collections.reverse(messages);
        return messages;
    }

    private Conversation findOwnedConversation(String ownerId, String conversationId) {
        return conversationRepository.findByIdAndOwner_Id(conversationId, ownerId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND",
                        "Conversation was not found."
                ));
    }
}
