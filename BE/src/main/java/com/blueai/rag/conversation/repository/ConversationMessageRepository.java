package com.blueai.rag.conversation.repository;

import com.blueai.rag.conversation.model.ConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    List<ConversationMessage> findByConversation_IdOrderByCreatedAtDescIdDesc(String conversationId, Pageable pageable);

    List<ConversationMessage> findByConversation_IdOrderByCreatedAtAscIdAsc(String conversationId, Pageable pageable);

    long countByConversation_Id(String conversationId);

    Optional<ConversationMessage> findFirstByConversation_IdOrderByCreatedAtDescIdDesc(String conversationId);

    Optional<ConversationMessage> findByIdAndConversation_Owner_Id(String messageId, String ownerId);

    void deleteByConversation_Id(String conversationId);
}
