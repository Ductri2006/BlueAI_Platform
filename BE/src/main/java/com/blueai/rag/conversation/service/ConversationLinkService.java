package com.blueai.rag.conversation.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.conversation.dto.ConversationContextSettingsResponse;
import com.blueai.rag.conversation.dto.ConversationContextSettingsRequest;
import com.blueai.rag.conversation.dto.ConversationLinkResponse;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationLink;
import com.blueai.rag.conversation.repository.ConversationLinkRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationLinkService {

    private final ConversationRepository conversationRepository;
    private final ConversationLinkRepository linkRepository;
    private final ConversationContextSnapshotFactory snapshotFactory;
    private final UserRepository userRepository;

    public ConversationLinkService(
            ConversationRepository conversationRepository,
            ConversationLinkRepository linkRepository,
            ConversationContextSnapshotFactory snapshotFactory,
            UserRepository userRepository
    ) {
        this.conversationRepository = conversationRepository;
        this.linkRepository = linkRepository;
        this.snapshotFactory = snapshotFactory;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ConversationContextSettingsResponse getSettings(String ownerId, String conversationId) {
        Conversation conversation = findOwnedConversation(ownerId, conversationId);
        return settingsResponse(conversation);
    }

    @Transactional
    public ConversationContextSettingsResponse updateSettings(
            String ownerId,
            String conversationId,
            ConversationContextSettingsRequest request
    ) {
        Conversation conversation = findOwnedConversation(ownerId, conversationId);
        conversation.updateContextMode(request.mode());
        return settingsResponse(conversation);
    }

    @Transactional
    public ConversationLinkResponse createLink(String ownerId, String targetConversationId, String sourceConversationId) {
        Conversation target = findOwnedConversation(ownerId, targetConversationId);
        Conversation source = findOwnedConversation(ownerId, sourceConversationId);
        if (target.getId().equals(source.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONVERSATION_LINK_SELF_REFERENCE", "A conversation cannot link to itself.");
        }
        if (linkRepository.existsByTargetConversation_IdAndSourceConversation_Id(target.getId(), source.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_LINK_EXISTS", "This conversation is already linked.");
        }

        ConversationContextSnapshotFactory.Snapshot snapshot = snapshotFactory.capture(source);
        ConversationLink link = linkRepository.saveAndFlush(new ConversationLink(
                target,
                source,
                snapshot.lastMessageId(),
                snapshot.content()
        ));
        return ConversationLinkResponse.from(link);
    }

    @Transactional
    public ConversationLinkResponse refreshLink(String ownerId, String targetConversationId, String linkId) {
        Conversation target = findOwnedConversation(ownerId, targetConversationId);
        ConversationLink link = linkRepository.findByIdAndTargetConversation_Id(linkId, target.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_LINK_NOT_FOUND", "Conversation link was not found."));
        // Re-check the source rather than trusting a historical association.
        findOwnedConversation(ownerId, link.getSourceConversation().getId());
        ConversationContextSnapshotFactory.Snapshot snapshot = snapshotFactory.capture(link.getSourceConversation());
        link.refreshSnapshot(snapshot.lastMessageId(), snapshot.content());
        return ConversationLinkResponse.from(link);
    }

    @Transactional
    public void deleteLink(String ownerId, String targetConversationId, String linkId) {
        Conversation target = findOwnedConversation(ownerId, targetConversationId);
        ConversationLink link = linkRepository.findByIdAndTargetConversation_Id(linkId, target.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_LINK_NOT_FOUND", "Conversation link was not found."));
        linkRepository.delete(link);
    }

    private ConversationContextSettingsResponse settingsResponse(Conversation conversation) {
        List<ConversationLinkResponse> links = linkRepository
                .findByTargetConversation_IdOrderByCreatedAtDesc(conversation.getId())
                .stream()
                .map(ConversationLinkResponse::from)
                .toList();
        return ConversationContextSettingsResponse.from(conversation, links);
    }

    private Conversation findOwnedConversation(String ownerId, String conversationId) {
        Conversation conversation = conversationRepository.findByIdAndOwner_Id(conversationId, ownerId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND",
                        "Conversation was not found."
                ));
        // A signed access token can outlive an administrator disabling its owner.
        var owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User no longer exists."));
        if (owner.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "User account is disabled.");
        }
        return conversation;
    }
}
