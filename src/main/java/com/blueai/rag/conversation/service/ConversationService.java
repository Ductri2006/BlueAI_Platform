package com.blueai.rag.conversation.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.conversation.dto.ConversationResponse;
import com.blueai.rag.conversation.dto.CreateConversationRequest;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.repository.ConversationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationService {

    private static final String DEFAULT_TITLE = "New conversation";

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;

    public ConversationService(UserRepository userRepository, ConversationRepository conversationRepository) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
    }

    @Transactional
    public ConversationResponse create(String ownerId, CreateConversationRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User no longer exists."));
        if (owner.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "User account is disabled.");
        }

        String title = request.title() == null || request.title().isBlank()
                ? DEFAULT_TITLE
                : request.title().trim();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(owner, title));
        return ConversationResponse.from(conversation);
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(String ownerId) {
        return conversationRepository.findByOwner_IdOrderByUpdatedAtDesc(ownerId)
                .stream()
                .map(ConversationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationResponse get(String ownerId, String conversationId) {
        Conversation conversation = conversationRepository.findByIdAndOwner_Id(conversationId, ownerId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND",
                        "Conversation was not found."
                ));
        return ConversationResponse.from(conversation);
    }
}
