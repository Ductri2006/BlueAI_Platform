package com.blueai.rag.document.repository;

import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.auth.model.UserRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RagDocumentRepository extends JpaRepository<RagDocument, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select document
            from RagDocument document
            where document.status = :status
              and document.deletedAt is null
              and (document.nextAttemptAt is null or document.nextAttemptAt <= :now)
            order by document.createdAt asc
            """)
    List<RagDocument> findForClaim(
            @Param("status") DocumentStatus status,
            @Param("now") java.time.Instant now,
            Pageable pageable
    );

    @Query("""
            select document from RagDocument document
            where document.owner.id = :ownerId and document.deletedAt is null
            order by document.createdAt desc
            """)
    List<RagDocument> findByOwner_IdOrderByCreatedAtDesc(@Param("ownerId") String ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select document from RagDocument document
            where document.status = :status
              and document.processingStartedAt < :cutoff
              and document.deletedAt is null
            """)
    List<RagDocument> findByStatusAndProcessingStartedAtBefore(
            @Param("status") DocumentStatus status,
            @Param("cutoff") java.time.Instant cutoff
    );

    @Query("""
            select document from RagDocument document
            where document.owner.id = :ownerId
              and document.status = :status
              and document.deletedAt is null
            order by document.createdAt desc
            """)
    List<RagDocument> findByOwner_IdAndStatusOrderByCreatedAtDesc(
            @Param("ownerId") String ownerId,
            @Param("status") DocumentStatus status
    );

    @Query("""
            select document from RagDocument document
            where document.id = :documentId
              and document.owner.id = :ownerId
              and document.deletedAt is null
            """)
    Optional<RagDocument> findByIdAndOwner_Id(
            @Param("documentId") String documentId,
            @Param("ownerId") String ownerId
    );

    @Query("""
            select document from RagDocument document
            where document.id = :documentId and document.owner.id = :ownerId
            """)
    Optional<RagDocument> findByIdAndOwnerIncludingDeleted(
            @Param("documentId") String documentId,
            @Param("ownerId") String ownerId
    );

    @Query("select document from RagDocument document where document.id = :documentId")
    Optional<RagDocument> findByIdIncludingDeleted(@Param("documentId") String documentId);

    @Query("""
            select document from RagDocument document
            where document.id = :documentId
              and document.owner.role = :role
              and document.status = :status
              and document.deletedAt is null
            """)
    Optional<RagDocument> findByIdAndOwner_RoleAndStatus(
            @Param("documentId") String documentId,
            @Param("role") UserRole role,
            @Param("status") DocumentStatus status
    );

    @Query("""
            select document from RagDocument document
            where document.id = :documentId
              and document.status = :status
              and document.processingAttemptId = :attemptId
              and document.deletedAt is null
            """)
    Optional<RagDocument> findClaimedByIdAndAttempt(
            @Param("documentId") String documentId,
            @Param("status") DocumentStatus status,
            @Param("attemptId") String attemptId
    );

    @Query("""
            select document from RagDocument document
            where document.deletedAt is not null and document.deletedAt < :cutoff
            order by case when document.purgeAttemptedAt is null then 0 else 1 end asc,
                     document.purgeAttemptedAt asc,
                     document.deletedAt asc,
                     document.id asc
            """)
    List<RagDocument> findDeletedBefore(@Param("cutoff") java.time.Instant cutoff, Pageable pageable);

    @Query("""
            select document from RagDocument document
            where document.owner.id = :ownerId
              and document.deletedAt is null
              and (:status is null or document.status = :status)
              and (:search = '' or lower(document.originalFilename) like lower(concat('%', :search, '%')) escape '\\')
            """)
    Page<RagDocument> searchActive(
            @Param("ownerId") String ownerId,
            @Param("status") DocumentStatus status,
            @Param("search") String search,
            Pageable pageable
    );

    @Query("select count(document) from RagDocument document where document.status = :status and document.deletedAt is null")
    long countByStatus(@Param("status") DocumentStatus status);

    @Query("select count(document) from RagDocument document where document.status in :statuses and document.deletedAt is null")
    long countByStatusIn(@Param("statuses") List<DocumentStatus> statuses);
}
