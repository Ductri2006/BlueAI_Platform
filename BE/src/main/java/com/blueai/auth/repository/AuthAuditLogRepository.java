package com.blueai.auth.repository;

import com.blueai.auth.model.AuthAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuthAuditLogRepository extends JpaRepository<AuthAuditLog, String> {

    @Query("""
            select log from AuthAuditLog log
             where (:action is null or log.action = :action)
               and (:actorUserId is null or log.actorUserId = :actorUserId)
               and (:targetUserId is null or log.targetUserId = :targetUserId)
               and (:from is null or log.createdAt >= :from)
               and (:to is null or log.createdAt < :to)
            """)
    Page<AuthAuditLog> search(
            @Param("action") String action,
            @Param("actorUserId") String actorUserId,
            @Param("targetUserId") String targetUserId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AuthAuditLog log where log.createdAt < :cutoff")
    int purgeBefore(@Param("cutoff") Instant cutoff);
}
