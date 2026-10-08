package com.blueai.ai.task.service;

import com.blueai.ai.task.repository.AiTaskRepository;
import com.blueai.ai.task.repository.IntegrationClientRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable expiration, shared by submission, polling and the background sweep. */
@Service
public class AiTaskRecoveryService {
    private static final Logger LOG = LoggerFactory.getLogger(AiTaskRecoveryService.class);
    // Allow a bounded interval for output validation and committing the result.
    public static final Duration FINALIZATION_GRACE = Duration.ofSeconds(15);
    public static final String EXPIRED_CODE = "AI_TASK_EXPIRED";

    private final AiTaskRepository ledger;
    private final IntegrationClientRepository clients;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private ExpiryCursor cursor;
    private Instant cycleCutoff;

    public AiTaskRecoveryService(
            AiTaskRepository ledger,
            IntegrationClientRepository clients,
            ObjectMapper mapper,
            TransactionTemplate transactions) {
        this.ledger = ledger;
        this.clients = clients;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public Instant databaseNow() {
        return ledger.databaseTimestamp().toInstant();
    }

    public int recoverClient(String clientId) {
        Integer recovered =
                transactions.execute(
                        status -> {
                            // Every claim, completion and recovery uses the same first row lock.
                            List<String> clients = this.clients.lockClientNowait(clientId);
                            return clients.isEmpty() ? 0 : expireLockedClient(clientId);
                        });
        return recovered == null ? 0 : recovered;
    }

    public int expireLockedClient(String clientId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Task recovery requires the client's transaction lock.");
        }
        Instant now = databaseNow();
        List<Map<String, Object>> expired = ledger.findExpiredForClient(clientId, now);
        int recovered = 0;
        for (Map<String, Object> row : expired) {
            String id = (String) row.get("id");
            String response = expiredResponse(id, (String) row.get("capability"));
            recovered += ledger.expireTask(response, EXPIRED_CODE, now, id, clientId, now);
        }
        return recovered;
    }

    public synchronized int recoverExpiredTasks() {
        // A finite scan cycle must finish even while newer tasks keep expiring.
        // Otherwise a client skipped behind a busy lock can stay behind the
        // moving cursor indefinitely, despite its lock becoming available.
        if (cycleCutoff == null) cycleCutoff = databaseNow();
        List<Map<String, Object>> page = expiredPage(cycleCutoff);
        if (page.isEmpty() && cursor != null) {
            cursor = null;
            cycleCutoff = databaseNow();
            page = expiredPage(cycleCutoff);
        }
        if (page.isEmpty()) {
            cycleCutoff = null;
            return 0;
        }
        Map<String, Object> last = page.getLast();
        // Advance even if this whole page fails, so later healthy clients get a turn.
        cursor =
                new ExpiryCursor(
                        ((Timestamp) last.get("expires_at")).toInstant(), (String) last.get("id"));
        var clients = new LinkedHashSet<String>();
        for (Map<String, Object> row : page) clients.add((String) row.get("client_id"));
        int recovered = 0;
        for (String clientId : clients) {
            try {
                recovered += recoverClient(clientId);
            } catch (RuntimeException exception) {
                LOG.warn(
                        "Could not expire AI tasks for client {}; the next sweep will retry.",
                        clientId,
                        exception);
            }
        }
        return recovered;
    }

    private List<Map<String, Object>> expiredPage(Instant now) {
        if (cursor == null) {
            return ledger.findExpiredPage(now);
        }
        return ledger.findExpiredPageAfter(
                now, cursor.expiresAt(), cursor.expiresAt(), cursor.id());
    }

    private record ExpiryCursor(Instant expiresAt, String id) {}

    private String expiredResponse(String id, String capability) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", id);
        response.put("status", "FAILED");
        response.put("capability", capability);
        response.put("result", Map.of());
        response.put("citations", List.of());
        response.put("confidence", null);
        response.put("confidenceStatus", "UNAVAILABLE");
        response.put("usage", Map.of("status", "UNKNOWN"));
        response.put(
                "error",
                Map.of(
                        "code",
                        EXPIRED_CODE,
                        "message",
                        "Task expired before its result was saved. Poll or replay this key to"
                            + " inspect the failure; use a new Idempotency-Key only if you"
                            + " explicitly want a new provider request."));
        response.put("traceId", UUID.randomUUID().toString());
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize task expiration.", exception);
        }
    }
}
