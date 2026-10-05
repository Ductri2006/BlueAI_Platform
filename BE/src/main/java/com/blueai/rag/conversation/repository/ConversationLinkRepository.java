package com.blueai.rag.conversation.repository;

import com.blueai.rag.conversation.model.ConversationLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationLinkRepository extends JpaRepository<ConversationLink, String> {

    @Query("""
            select link from ConversationLink link
            where link.targetConversation.id = :targetConversationId
              and link.targetConversation.deletedAt is null
              and link.sourceConversation.deletedAt is null
            order by link.createdAt desc
            """)
    List<ConversationLink> findByTargetConversation_IdOrderByCreatedAtDesc(
            @Param("targetConversationId") String targetConversationId
    );

    @Query("""
            select link from ConversationLink link
            where link.id = :linkId
              and link.targetConversation.id = :targetConversationId
              and link.targetConversation.deletedAt is null
              and link.sourceConversation.deletedAt is null
            """)
    Optional<ConversationLink> findByIdAndTargetConversation_Id(
            @Param("linkId") String linkId,
            @Param("targetConversationId") String targetConversationId
    );

    @Query("""
            select case when count(link) > 0 then true else false end from ConversationLink link
            where link.targetConversation.id = :targetConversationId
              and link.sourceConversation.id = :sourceConversationId
              and link.targetConversation.deletedAt is null
              and link.sourceConversation.deletedAt is null
            """)
    boolean existsByTargetConversation_IdAndSourceConversation_Id(
            @Param("targetConversationId") String targetConversationId,
            @Param("sourceConversationId") String sourceConversationId
    );

    void deleteByTargetConversation_IdOrSourceConversation_Id(String targetConversationId, String sourceConversationId);
}
