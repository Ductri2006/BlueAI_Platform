package com.blueai.ai.task.repository;

import com.blueai.ai.task.model.AiClientPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** SQL persistence only. Callers own transaction boundaries and lock ordering. */
@Repository
public class IntegrationClientRepository {
    private final JdbcTemplate jdbc;

    public IntegrationClientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int revokeOwned(Instant now, String id, String ownerId) {
        return jdbc.update(
                "update ai_integration_clients set active=false,revoked_at=? where id=? and"
                    + " owner_id=? and revoked_at is null",
                now,
                id,
                ownerId);
    }

    public List<Map<String, Object>> listOwned(String ownerId, int size, long offset) {
        return jdbc.queryForList(
                "select id,name,active,created_at,revoked_at from ai_integration_clients where"
                    + " owner_id=? order by created_at desc limit ? offset ?",
                ownerId,
                size,
                offset);
    }

    public int grantDocument(String clientId, String documentId) {
        return jdbc.update(
                "insert into ai_integration_document_grants(client_id,document_id) values(?,?)",
                clientId,
                documentId);
    }

    public int grantCapability(String clientId, String capability) {
        return jdbc.update(
                "insert into ai_integration_capabilities(client_id,capability) values(?,?)",
                clientId,
                capability);
    }

    public int create(String id, String ownerId, String name, String hash, Instant now) {
        return jdbc.update(
                "insert into"
                    + " ai_integration_clients(id,owner_id,name,credential_hash,active,created_at,rate_count)"
                    + " values(?,?,?,?,true,?,0)",
                id,
                ownerId,
                name,
                hash,
                now);
    }

    public List<String> lockDocumentGrant(String clientId, String documentId) {
        return jdbc.queryForList(
                "select document_id from ai_integration_document_grants where client_id=? and"
                    + " document_id=? for update",
                String.class,
                clientId,
                documentId);
    }

    public List<Map<String, Object>> lockAuthorization(String clientId) {
        return jdbc.queryForList(
                "select active,revoked_at,owner_id from ai_integration_clients where id=? for"
                    + " update",
                clientId);
    }

    public List<Map<String, Object>> lockOwner(String ownerId) {
        return jdbc.queryForList(
                "select role,status,deleted_at from app_users where id=? for update", ownerId);
    }

    public String lockClientId(String clientId) {
        return jdbc.queryForObject(
                "select id from ai_integration_clients where id=? for update",
                String.class,
                clientId);
    }

    public int updateRateWindow(Instant window, int count, String clientId) {
        return jdbc.update(
                "update ai_integration_clients set rate_window_start=?,rate_count=? where id=?",
                window,
                count,
                clientId);
    }

    public List<String> lockCapabilities(String clientId) {
        return jdbc.queryForList(
                "select capability from ai_integration_capabilities where client_id=? for update",
                String.class,
                clientId);
    }

    public Map<String, Object> lockRateState(String clientId) {
        return jdbc.queryForMap(
                """
                select active,revoked_at,owner_id,rate_window_start,rate_count
                  from ai_integration_clients where id=? for update
                """,
                clientId);
    }

    public List<String> lockClientNowait(String clientId) {
        return jdbc.queryForList(
                "select id from ai_integration_clients where id=? for update nowait",
                String.class,
                clientId);
    }

    public List<AiClientPrincipal> findByCredentialHash(String hash) {
        return jdbc.query(
                """
                select c.id, c.owner_id from ai_integration_clients c
                join app_users u on u.id=c.owner_id
                where c.credential_hash=? and c.active=true and c.revoked_at is null
                  and u.status='ACTIVE' and u.deleted_at is null and u.role='ADMIN'
                """,
                (rs, row) -> new AiClientPrincipal(rs.getString(1), rs.getString(2)),
                hash);
    }
}
