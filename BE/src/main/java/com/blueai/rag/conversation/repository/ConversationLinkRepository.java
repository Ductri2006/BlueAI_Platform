package com.blueai.rag.conversation.repository;

import com.blueai.rag.conversation.model.ConversationLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationLinkRepository extends JpaRepository<ConversationLink, String> {

    List<ConversationLink> findByTargetConversation_IdOrderByCreatedAtDesc(String targetConversationId);

    Optional<ConversationLink> findByIdAndTargetConversation_Id(String linkId, String targetConversationId);

    boolean existsByTargetConversation_IdAndSourceConversation_Id(String targetConversationId, String sourceConversationId);

    void deleteByTargetConversation_IdOrSourceConversation_Id(String targetConversationId, String sourceConversationId);
}
