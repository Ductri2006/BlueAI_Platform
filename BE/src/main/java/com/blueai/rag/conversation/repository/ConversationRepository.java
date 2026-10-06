package com.blueai.rag.conversation.repository;

import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationContextMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

    @Query("""
            select conversation from Conversation conversation
            where conversation.owner.id = :ownerId and conversation.deletedAt is null
            order by conversation.updatedAt desc
            """)
    List<Conversation> findByOwner_IdOrderByUpdatedAtDesc(@Param("ownerId") String ownerId);

    @Query("""
            select conversation from Conversation conversation
            where conversation.owner.id = :ownerId and conversation.deletedAt is null
            order by conversation.updatedAt desc
            """)
    List<Conversation> findActiveRecent(@Param("ownerId") String ownerId, Pageable pageable);

    /** Preserve the old method name and its historical 50-row contract. */
    default List<Conversation> findTop50ByOwner_IdOrderByUpdatedAtDesc(String ownerId) {
        return findActiveRecent(ownerId, PageRequest.of(0, 50));
    }

    @Query("""
            select conversation from Conversation conversation
            where conversation.id = :conversationId
              and conversation.owner.id = :ownerId
              and conversation.deletedAt is null
            """)
    Optional<Conversation> findByIdAndOwner_Id(
            @Param("conversationId") String conversationId,
            @Param("ownerId") String ownerId
    );

    @Query("select conversation from Conversation conversation where conversation.id = :conversationId and conversation.owner.id = :ownerId")
    Optional<Conversation> findByIdAndOwnerIncludingDeleted(
            @Param("conversationId") String conversationId,
            @Param("ownerId") String ownerId
    );

    @Query("select conversation from Conversation conversation where conversation.id = :conversationId and conversation.deletedAt is null")
    @Override
    Optional<Conversation> findById(@Param("conversationId") String conversationId);

    @Query("""
            select conversation from Conversation conversation
            where conversation.owner.id = :ownerId
              and conversation.deletedAt is null
              and (:search = '' or lower(conversation.title) like lower(concat('%', :search, '%')) escape '\\')
              and (:contextMode is null or conversation.contextMode = :contextMode)
            """)
    Page<Conversation> searchActive(
            @Param("ownerId") String ownerId,
            @Param("search") String search,
            @Param("contextMode") ConversationContextMode contextMode,
            Pageable pageable
    );

    @Query("select conversation from Conversation conversation where conversation.deletedAt is not null and conversation.deletedAt < :cutoff order by conversation.deletedAt asc")
    List<Conversation> findDeletedBefore(@Param("cutoff") java.time.Instant cutoff, Pageable pageable);
}
