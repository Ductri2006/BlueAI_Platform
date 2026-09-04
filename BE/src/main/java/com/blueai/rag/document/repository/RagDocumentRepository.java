package com.blueai.rag.document.repository;

import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.auth.model.UserRole;
import jakarta.persistence.LockModeType;
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
            order by document.createdAt asc
            """)
    List<RagDocument> findForClaim(@Param("status") DocumentStatus status, Pageable pageable);

    List<RagDocument> findByOwner_IdOrderByCreatedAtDesc(String ownerId);

    List<RagDocument> findByOwner_IdAndStatusOrderByCreatedAtDesc(String ownerId, DocumentStatus status);

    Optional<RagDocument> findByIdAndOwner_Id(String documentId, String ownerId);

    Optional<RagDocument> findByIdAndOwner_RoleAndStatus(String documentId, UserRole role, DocumentStatus status);

    long countByStatus(DocumentStatus status);

    long countByStatusIn(List<DocumentStatus> statuses);
}
