package com.blueai.rag.conversation.repository;

import com.blueai.rag.conversation.model.ConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    List<ConversationMessage> findByConversation_IdOrderByCreatedAtDescIdDesc(String conversationId, Pageable pageable);
}
