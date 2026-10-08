package com.blueai.ai.task.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** SQL persistence only. Callers own transaction boundaries and lock ordering. */
@Repository
public class AiTaskRepository {
    private final JdbcTemplate jdbc;

    public AiTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> findEvidenceForUpdate(String taskId) {
        return jdbc.queryForList(
                "select document_id from ai_task_evidence where task_id=? for update",
                String.class,
                taskId);
    }

    public List<Map<String, Object>> lockDocument(String documentId, String ownerId) {
        return jdbc.queryForList(
                "select status,deleted_at from rag_documents where id=? and owner_id=? for update",
                documentId,
                ownerId);
    }

    public List<Map<String, Object>> findOwnedForUpdate(String id, String clientId) {
        return jdbc.queryForList(
                "select id,status,capability,response_json,created_at from ai_task_ledger where"
                    + " id=? and client_id=? for update",
                id,
                clientId);
    }

    public int failProcessing(
            String response,
            String code,
            Instant completedAt,
            String id,
            String clientId,
            Instant cutoff) {
        return jdbc.update(
                """
                update ai_task_ledger set status='FAILED',response_json=?,error_code=?,completed_at=?
                 where id=? and client_id=? and status='PROCESSING' and expires_at>?
                """,
                response,
                code,
                completedAt,
                id,
                clientId,
                cutoff);
    }

    public List<Map<String, Object>> lockDispatchState(String id, String clientId) {
        return jdbc.queryForList(
                """
                select status,expires_at from ai_task_ledger where id=? and client_id=? for update
                """,
                id,
                clientId);
    }

    public int addEvidence(String taskId, String documentId) {
        return jdbc.update(
                "insert into ai_task_evidence(task_id,document_id) values(?,?)",
                taskId,
                documentId);
    }

    public int finalizeProcessing(
            Object status,
            String response,
            Object errorCode,
            Instant completedAt,
            String id,
            String clientId,
            Instant cutoff) {
        return jdbc.update(
                """
                update ai_task_ledger set status=?,response_json=?,error_code=?,completed_at=?
                 where id=? and client_id=? and status='PROCESSING' and expires_at>?
                """,
                status,
                response,
                errorCode,
                completedAt,
                id,
                clientId,
                cutoff);
    }

    public String lockStatus(String id, String clientId) {
        return jdbc.queryForObject(
                "select status from ai_task_ledger where id=? and client_id=? for update",
                String.class,
                id,
                clientId);
    }

    public List<Map<String, Object>> findExistingForUpdate(String clientId, String key) {
        return jdbc.queryForList(
                "select id,request_digest,status,response_json from ai_task_ledger where"
                    + " client_id=? and idempotency_key=? for update",
                clientId,
                key);
    }

    public int insertProcessing(
            String id,
            String clientId,
            String key,
            String digest,
            String capability,
            Instant createdAt,
            Instant expiresAt) {
        return jdbc.update(
                """
                insert into ai_task_ledger(id,client_id,idempotency_key,request_digest,capability,status,created_at,expires_at)
                values(?,?,?,?,?,'PROCESSING',?,?)
                """,
                id,
                clientId,
                key,
                digest,
                capability,
                createdAt,
                expiresAt);
    }

    public Integer countProcessing(String clientId) {
        return jdbc.queryForObject(
                "select count(*) from ai_task_ledger where client_id=? and status='PROCESSING'",
                Integer.class,
                clientId);
    }

    public List<Map<String, Object>> findExpiredPageAfter(
            Instant cutoff, Instant expiresAt, Instant sameExpiresAt, String id) {
        return jdbc.queryForList(
                """
                select id,client_id,expires_at from ai_task_ledger
                 where status='PROCESSING' and expires_at<=?
                   and (expires_at>? or (expires_at=? and id>?))
                 order by expires_at,id limit 100
                """,
                cutoff,
                expiresAt,
                sameExpiresAt,
                id);
    }

    public List<Map<String, Object>> findExpiredPage(Instant cutoff) {
        return jdbc.queryForList(
                """
                select id,client_id,expires_at from ai_task_ledger
                 where status='PROCESSING' and expires_at<=?
                 order by expires_at,id limit 100
                """,
                cutoff);
    }

    public int expireTask(
            String response,
            String code,
            Instant completedAt,
            String id,
            String clientId,
            Instant cutoff) {
        return jdbc.update(
                """
                update ai_task_ledger set status='FAILED',response_json=?,error_code=?,completed_at=?
                 where id=? and client_id=? and status='PROCESSING' and expires_at<=?
                """,
                response,
                code,
                completedAt,
                id,
                clientId,
                cutoff);
    }

    public List<Map<String, Object>> findExpiredForClient(String clientId, Instant now) {
        return jdbc.queryForList(
                """
                select id,capability from ai_task_ledger
                 where client_id=? and status='PROCESSING' and expires_at<=?
                """,
                clientId,
                now);
    }

    public Timestamp databaseTimestamp() {
        return jdbc.queryForObject("select current_timestamp(6)", Timestamp.class);
    }
}
