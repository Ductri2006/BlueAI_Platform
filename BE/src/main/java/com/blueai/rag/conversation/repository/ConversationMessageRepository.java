package com.blueai.rag.conversation.repository;

import com.blueai.rag.conversation.model.ConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    @Query("""
            select message from ConversationMessage message
            where message.conversation.id = :conversationId
              and message.conversation.deletedAt is null
            order by message.createdAt desc, message.id desc
            """)
    List<ConversationMessage> findByConversation_IdOrderByCreatedAtDescIdDesc(
            @Param("conversationId") String conversationId, Pageable pageable
    );

    @Query("""
            select message from ConversationMessage message
            where message.conversation.id = :conversationId
              and message.conversation.deletedAt is null
            order by message.createdAt asc, message.id asc
            """)
    List<ConversationMessage> findByConversation_IdOrderByCreatedAtAscIdAsc(
            @Param("conversationId") String conversationId, Pageable pageable
    );

    @Query("select count(message) from ConversationMessage message where message.conversation.id = :conversationId and message.conversation.deletedAt is null")
    long countByConversation_Id(@Param("conversationId") String conversationId);

    @Query("""
            select message from ConversationMessage message
            where message.conversation.id = :conversationId
              and message.conversation.deletedAt is null
            order by message.createdAt desc, message.id desc
            """)
    Optional<ConversationMessage> findFirstByConversation_IdOrderByCreatedAtDescIdDesc(
            @Param("conversationId") String conversationId
    );

    @Query("""
            select message from ConversationMessage message
            where message.id = :messageId
              and message.conversation.owner.id = :ownerId
              and message.conversation.deletedAt is null
            """)
    Optional<ConversationMessage> findByIdAndConversation_Owner_Id(
            @Param("messageId") String messageId, @Param("ownerId") String ownerId
    );

    void deleteByConversation_Id(String conversationId);
}
