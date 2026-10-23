package com.blueai.ai.task.service;

import static com.blueai.ai.task.dto.AiTaskRequestValidator.*;

import com.blueai.ai.task.dto.ExternalChatContext;
import com.blueai.ai.task.exception.AiTaskException;
import com.blueai.ai.task.model.AiClientPrincipal;
import com.blueai.ai.task.repository.IntegrationClientRepository;
import com.blueai.ai.task.security.CredentialHasher;
import com.blueai.ai.task.security.IntegrationSessionCipher;
import com.blueai.auth.exception.ApiException;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.blueai.studio.dto.BotStudioDtos.RuntimeMessageRequest;
import com.blueai.studio.model.Bot;
import com.blueai.studio.model.BotSession;
import com.blueai.studio.model.BotStatus;
import com.blueai.studio.repository.BotRepository;
import com.blueai.studio.repository.BotReleaseRepository;
import com.blueai.studio.repository.BotSessionRepository;
import com.blueai.studio.service.BotRuntimeService;
import com.blueai.studio.service.BotSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Machine-authenticated bridge to the same published dialog/Core runtime used by widgets. */
@Service
public class IntegrationBotService {
    private final IntegrationClientRepository clients;
    private final BotRepository bots;
    private final BotReleaseRepository releases;
    private final BotSessionRepository sessions;
    private final RagDocumentRepository documents;
    private final BotRuntimeService runtime;
    private final IntegrationSessionCipher cipher;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IntegrationBotService(IntegrationClientRepository clients, BotRepository bots,
            BotReleaseRepository releases, BotSessionRepository sessions, RagDocumentRepository documents,
            BotRuntimeService runtime, IntegrationSessionCipher cipher, ObjectMapper mapper,
            JdbcTemplate jdbc, TransactionTemplate tx) {
        this.clients = clients; this.bots = bots; this.releases = releases; this.sessions = sessions;
        this.documents = documents; this.runtime = runtime; this.cipher = cipher;
        this.mapper = mapper; this.jdbc = jdbc; this.tx = tx;
    }

    public Map<String, Object> create(AiClientPrincipal client, String botId, String key, JsonNode body) {
        requireKeys(body, Set.of("externalContext"));
        Map<String, String> context = context(body);
        if (key == null || key.isBlank() || key.length() > 128)
            throw bad("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 1..128 characters.");
        String digest = hash(botId + "\n" + json(new TreeMap<>(context)));
        return tx.execute(status -> {
            authorizeClient(client, context);
            List<Map<String, Object>> previous = jdbc.queryForList(
                    "select * from ai_integration_bot_sessions where client_id=? and creation_key=? for update",
                    client.clientId(), key);
            if (!previous.isEmpty()) {
                Map<String, Object> row = previous.getFirst();
                if (!digest.equals(row.get("request_digest")))
                    throw new AiTaskException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Session key has different content.");
                BotSession session = session((String) row.get("session_id"));
                authorizeBot(client, session.getBot(), session.getReleaseId());
                return read((String) row.get("response_json"));
            }
            Bot bot = bots.findAdminWorkspaceBotForUpdate(botId, client.ownerId())
                    .filter(item -> item.getOwner().getId().equals(client.ownerId()))
                    .orElseThrow(() -> denied("BOT_NOT_ALLOWED"));
            authorizeBot(client, bot, bot.getPublishedReleaseId());
            var created = runtimeCall(() -> runtime.createSession(bot.getPublicKey(), "integration:" + client.clientId()));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sessionId", created.sessionId()); response.put("releaseId", created.releaseId());
            response.put("revision", created.revision()); response.put("welcome", created.welcome());
            response.put("expiresAt", created.expiresAt()); response.put("externalContext", context);
            jdbc.update("""
                    insert into ai_integration_bot_sessions(session_id,client_id,creation_key,request_digest,
                        binding_json,token_cipher,response_json) values(?,?,?,?,?,?,?)
                    """, created.sessionId(), client.clientId(), key, digest, json(identity(context)),
                    cipher.encrypt(created.sessionId(), created.accessToken()), json(response));
            return response;
        });
    }

    public Map<String, Object> message(AiClientPrincipal client, String sessionId, JsonNode body) {
        requireKeys(body, Set.of("externalContext", "input"));
        Map<String, String> context = context(body);
        requireKeys(body.path("input"), Set.of("text"));
        String text = requiredText(body.path("input"), "text", 2000);
        String encrypted = tx.execute(status -> authorizedSession(client, sessionId, context));
        String requestId = hash(json(new TreeMap<>(context)));
        var reply = runtimeCall(() -> runtime.message(sessionId, cipher.decrypt(sessionId, encrypted),
                new RuntimeMessageRequest(requestId, text), "integration:" + client.clientId()));
        // Recheck grants and ownership before exposing a newly generated or replayed answer.
        tx.executeWithoutResult(status -> authorizedSession(client, sessionId, context));
        return Map.of("sessionId", reply.sessionId(), "requestId", reply.requestId(),
                "releaseId", reply.releaseId(), "revision", reply.revision(),
                "result", reply.result(), "externalContext", context);
    }

    private String authorizedSession(AiClientPrincipal client, String id, Map<String, String> context) {
        authorizeClient(client, context);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select binding_json,token_cipher from ai_integration_bot_sessions where session_id=? and client_id=?",
                id, client.clientId());
        if (rows.isEmpty()) throw new AiTaskException(HttpStatus.NOT_FOUND, "INTEGRATION_SESSION_NOT_FOUND", "Session was not found.");
        Map<String, Object> row = rows.getFirst();
        if (!read((String) row.get("binding_json")).equals(identity(context)))
            throw denied("EXTERNAL_CONVERSATION_MISMATCH");
        BotSession session = session(id);
        authorizeBot(client, session.getBot(), session.getReleaseId());
        return (String) row.get("token_cipher");
    }

    private void authorizeClient(AiClientPrincipal client, Map<String, String> context) {
        Map<String, Object> state = clients.lockRateState(client.clientId());
        if (!Boolean.TRUE.equals(state.get("active")) || state.get("revoked_at") != null
                || !client.ownerId().equals(state.get("owner_id"))) throw denied("CLIENT_INACTIVE");
        var owners = clients.lockOwner(client.ownerId());
        if (owners.isEmpty() || !"ADMIN".equals(owners.getFirst().get("role"))
                || !"ACTIVE".equals(owners.getFirst().get("status"))
                || owners.getFirst().get("deleted_at") != null) throw denied("OWNER_INACTIVE");
        if (!clients.lockCapabilities(client.clientId()).contains("BOT_CHAT")) throw denied("CAPABILITY_NOT_ALLOWED");
        Map<String, Object> binding = clients.externalBinding(client.clientId());
        if (!context.get("system").equals(binding.get("external_system"))
                || !context.get("companyId").equals(binding.get("external_company_id")))
            throw denied("EXTERNAL_COMPANY_MISMATCH");
    }

    private void authorizeBot(AiClientPrincipal client, Bot bot, String releaseId) {
        if (!bot.getOwner().getId().equals(client.ownerId()) || bot.getStatus() != BotStatus.PUBLISHED || releaseId == null)
            throw denied("BOT_NOT_ALLOWED");
        if (jdbc.queryForList("select bot_id from ai_integration_bot_grants where client_id=? and bot_id=?",
                String.class, client.clientId(), bot.getId()).isEmpty()) throw denied("BOT_NOT_ALLOWED");
        var release = releases.findByIdAndBot_Id(releaseId, bot.getId()).orElseThrow(() -> denied("BOT_NOT_ALLOWED"));
        BotSnapshot snapshot;
        try { snapshot = mapper.readValue(release.getConfigurationSnapshot(), BotSnapshot.class); }
        catch (Exception exception) { throw denied("BOT_CONFIGURATION_UNAVAILABLE"); }
        if (!client.ownerId().equals(snapshot.ownerId()) || !bot.getId().equals(snapshot.botId()))
            throw denied("BOT_NOT_ALLOWED");
        for (String docId : snapshot.publicDocumentIds()) {
            if (clients.lockDocumentGrant(client.clientId(), docId).isEmpty()) throw denied("BOT_DOCUMENT_NOT_ALLOWED");
            var doc = documents.findByIdAndOwner_Id(docId, client.ownerId()).orElseThrow(() -> denied("BOT_DOCUMENT_NOT_ALLOWED"));
            if (doc.getStatus() != DocumentStatus.READY || doc.isDeleted()) throw denied("BOT_DOCUMENT_NOT_ALLOWED");
        }
    }

    private BotSession session(String id) {
        BotSession session = sessions.findById(id).orElseThrow(() -> new AiTaskException(
                HttpStatus.NOT_FOUND, "INTEGRATION_SESSION_NOT_FOUND", "Session was not found."));
        if (!session.getExpiresAt().isAfter(Instant.now())) throw new AiTaskException(
                HttpStatus.GONE, "SESSION_EXPIRED", "Create a new session with a new creation key.");
        return session;
    }

    private Map<String, String> context(JsonNode body) {
        Map<String, String> value = ExternalChatContext.parse(body.path("externalContext"));
        if (value.isEmpty()) throw bad("EXTERNAL_CONTEXT_REQUIRED", "externalContext is required.");
        return value;
    }

    private Map<String, String> identity(Map<String, String> context) {
        Map<String, String> value = new TreeMap<>(context); value.remove("messageId"); return value;
    }

    private String hash(String value) { return HexFormat.of().formatHex(CredentialHasher.sha256(value)); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Integration context serialization failed.", exception); }
    }
    private Map<String, Object> read(String value) {
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new AiTaskException(HttpStatus.SERVICE_UNAVAILABLE,
                "INTEGRATION_SESSION_UNAVAILABLE", "Saved integration session is unavailable."); }
    }
    private AiTaskException denied(String code) {
        return new AiTaskException(HttpStatus.FORBIDDEN, code, "Integration is not authorized for this resource or context.");
    }
    private <T> T runtimeCall(Supplier<T> call) {
        try { return call.get(); }
        catch (ApiException exception) { throw new AiTaskException(exception.getStatus(), exception.getCode(), exception.getMessage()); }
    }
}
