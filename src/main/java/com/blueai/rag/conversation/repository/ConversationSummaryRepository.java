package com.blueai.rag.conversation.repository;

import com.blueai.rag.conversation.model.ConversationSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, String> {

    Optional<ConversationSummary> findByConversation_Id(String conversationId);
}
