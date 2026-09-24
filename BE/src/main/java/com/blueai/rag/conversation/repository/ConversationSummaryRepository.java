package com.blueai.rag.conversation.repository;

import com.blueai.rag.conversation.model.ConversationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, String> {

    @Query("""
            select summary from ConversationSummary summary
            where summary.conversation.id = :conversationId
              and summary.conversation.deletedAt is null
            """)
    Optional<ConversationSummary> findByConversation_Id(@Param("conversationId") String conversationId);

    void deleteByConversation_Id(String conversationId);
}
