package com.blueai.ai.task.service;

import static com.blueai.ai.task.dto.AiTaskRequestValidator.*;

import com.blueai.ai.task.model.PublishedApiPrincipal;
import com.blueai.ai.task.security.CredentialHasher;
import com.blueai.ai.task.security.IntegrationSessionCipher;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.studio.dto.BotStudioDtos.RuntimeMessageRequest;
import com.blueai.studio.model.Bot;
import com.blueai.studio.model.BotRelease;
import com.blueai.studio.repository.BotRepository;
import com.blueai.studio.repository.BotSessionRepository;
import com.blueai.studio.service.BotRuntimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class PublishedBotApiService {
    private final JdbcTemplate jdbc;
    private final BotRepository bots;
    private final UserRepository users;
    private final BotSessionRepository sessions;
    private final BotRuntimeService runtime;
    private final IntegrationSessionCipher cipher;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final int defaultLimit;
    private final SecureRandom random = new SecureRandom();

    public PublishedBotApiService(JdbcTemplate jdbc, BotRepository bots, UserRepository users,
            BotSessionRepository sessions, BotRuntimeService runtime, IntegrationSessionCipher cipher,
            ObjectMapper mapper, TransactionTemplate tx,
            @Value("${APP_API_KEY_REQUESTS_PER_MINUTE:30}") int defaultLimit) {
        if (defaultLimit < 1 || defaultLimit > 1000) throw new IllegalArgumentException("API key default must be 1..1000 requests per minute.");
        this.jdbc=jdbc; this.bots=bots; this.users=users; this.sessions=sessions; this.runtime=runtime;
        this.cipher=cipher; this.mapper=mapper; this.tx=tx; this.defaultLimit=defaultLimit;
    }

    /** Called inside Studio's publication transaction. A bot keeps its key on republish. */
    public void publish(Bot bot, BotRelease release) {
        int limit = draftLimit(bot.getId());
        var rows = jdbc.queryForList("select id from studio_bot_api_keys where bot_id=? for update", bot.getId());
        if (rows.isEmpty()) {
            String id = UUID.randomUUID().toString(); byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String key = "blueai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            jdbc.update("""
                    insert into studio_bot_api_keys(id,bot_id,credential_hash,credential_cipher,requests_per_minute,published_release_id)
                    values(?,?,?,?,?,?)
                    """, id, bot.getId(), hash(key), cipher.encrypt("api-key:"+id, key), limit, release.getId());
        } else {
            jdbc.update("update studio_bot_api_keys set requests_per_minute=?,published_release_id=? where bot_id=?",
                    limit, release.getId(), bot.getId());
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> settings(String actorId, String botId) {
        requireAdminWorkspaceBot(actorId, botId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestsPerMinute", draftLimit(botId)); result.put("defaultRequestsPerMinute", defaultLimit);
        var rows = jdbc.queryForList("select id,credential_cipher,requests_per_minute,published_release_id from studio_bot_api_keys where bot_id=?", botId);
        result.put("published", !rows.isEmpty());
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            result.put("apiKey", cipher.decrypt("api-key:"+row.get("id"), (String) row.get("credential_cipher")));
            result.put("publishedRequestsPerMinute", row.get("requests_per_minute"));
            result.put("releaseId", row.get("published_release_id"));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> saveSettings(String actorId, String botId, JsonNode body) {
        requireKeys(body, Set.of("requestsPerMinute"));
        JsonNode limit = body.path("requestsPerMinute");
        if (!limit.isIntegralNumber() || !limit.canConvertToInt() || limit.intValue()<1 || limit.intValue()>1000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_API_RATE_LIMIT", "Giới hạn API phải là số nguyên từ 1 đến 1.000 request/phút.");
        requireAdminWorkspaceBot(actorId, botId);
        // Use the bot lock for draft updates and publication, so competing requests cannot lose settings.
        bots.findAdminWorkspaceBotForUpdate(botId, actorId).orElseThrow(() -> unavailable());
        var rows = jdbc.queryForList("select bot_id from studio_bot_api_settings where bot_id=?", botId);
        if (rows.isEmpty()) jdbc.update("insert into studio_bot_api_settings(bot_id,requests_per_minute) values(?,?)", botId, limit.intValue());
        else jdbc.update("update studio_bot_api_settings set requests_per_minute=? where bot_id=?", limit.intValue(), botId);
        return settings(actorId, botId);
    }

    public Map<String, Object> chat(PublishedApiPrincipal principal, JsonNode body) {
        requireKeys(body, Set.of("userId", "message", "conversationId", "messageId", "channel"));
        String userId = identifier(body, "userId", null, 128);
        String text = requiredText(body, "message", 2000);
        String conversationId = identifier(body, "conversationId", userId, 128);
        String messageId = identifier(body, "messageId", UUID.randomUUID().toString(), 128);
        String channel = identifier(body, "channel", "api", 40);
        String contextHash = hash(json(List.of(principal.keyId(), userId, conversationId, channel)));
        Map<String, String> binding = tx.execute(status -> {
            requireLiveKey(principal);
            var rows = jdbc.queryForList("select session_id,token_cipher from studio_bot_api_sessions where context_hash=? for update", contextHash);
            if (!rows.isEmpty()) {
                var row = rows.getFirst();
                var session = sessions.findById((String) row.get("session_id"));
                if (session.isPresent() && session.get().getExpiresAt().isAfter(Instant.now())
                        && session.get().getReleaseId().equals(bots.findById(principal.botId()).orElseThrow(() -> unavailable()).getPublishedReleaseId())) {
                    return Map.of("id", (String) row.get("session_id"), "token", (String) row.get("token_cipher"));
                }
                jdbc.update("delete from studio_bot_api_sessions where context_hash=?", contextHash);
            }
            Bot bot = bots.findById(principal.botId()).orElseThrow(() -> unavailable());
            var created = runtime.createIntegrationSession(bot.getPublicKey());
            String encrypted = cipher.encrypt(created.sessionId(), created.accessToken());
            jdbc.update("insert into studio_bot_api_sessions(context_hash,api_key_id,session_id,token_cipher) values(?,?,?,?)",
                    contextHash, principal.keyId(), created.sessionId(), encrypted);
            return Map.of("id", created.sessionId(), "token", encrypted);
        });
        String id = binding.get("id");
        var reply = runtime.integrationMessage(id, cipher.decrypt(id, binding.get("token")),
                new RuntimeMessageRequest(hash(messageId), text));
        tx.executeWithoutResult(status -> requireLiveKey(principal));
        return Map.of("answer", reply.result().message(), "fallback", reply.result().fallback(),
                "userId", userId, "conversationId", conversationId, "messageId", messageId,
                "botId", principal.botId(), "sessionId", id, "releaseId", reply.releaseId());
    }

    private void requireLiveKey(PublishedApiPrincipal principal) {
        requireOwner(principal.ownerId(), principal.botId());
        // Match Studio publication's bot -> key lock order, including when creating a runtime session.
        bots.findAdminWorkspaceBotForUpdate(principal.botId(), principal.ownerId()).orElseThrow(() -> unavailable());
        var rows = jdbc.queryForList("select bot_id from studio_bot_api_keys where id=? for update", principal.keyId());
        if (rows.isEmpty() || !principal.botId().equals(rows.getFirst().get("bot_id"))) throw unavailable();
    }

    /** Admin management shares Studio's workspace; runtime key ownership remains separate below. */
    private Bot requireAdminWorkspaceBot(String actorId, String botId) {
        var actor = users.findById(actorId)
                .filter(user -> user.getRole() == UserRole.ADMIN && user.getStatus() == UserStatus.ACTIVE && !user.isDeleted())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "API_KEY_ADMIN_REQUIRED",
                        "Tài khoản quản trị đang hoạt động là bắt buộc."));
        return bots.findAdminWorkspaceBot(botId, actor.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "API_KEY_ADMIN_REQUIRED",
                        "Chỉ được xem và cấu hình API key của bot trong kho quản trị dùng chung."));
    }

    private Bot requireOwner(String actorId, String botId) {
        var user = users.findById(actorId).filter(u -> u.getRole()==UserRole.ADMIN && u.getStatus()==UserStatus.ACTIVE && !u.isDeleted())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,"API_KEY_OWNER_REQUIRED","Tài khoản quản trị đang hoạt động là bắt buộc."));
        return bots.findByIdAndOwner_Id(botId, user.getId()).orElseThrow(() -> new ApiException(
                HttpStatus.FORBIDDEN,"API_KEY_OWNER_REQUIRED","Chỉ chủ sở hữu bot được xem và cấu hình API key."));
    }
    private int draftLimit(String botId) {
        List<Integer> rows = jdbc.queryForList("select requests_per_minute from studio_bot_api_settings where bot_id=?", Integer.class, botId);
        return rows.isEmpty() ? defaultLimit : rows.getFirst();
    }
    private String identifier(JsonNode body, String field, String fallback, int max) {
        if (!body.has(field) && fallback != null) return fallback;
        String value = requiredText(body, field, max);
        if (!value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl))
            throw bad("INVALID_EXTERNAL_IDENTIFIER", "Identifiers must not contain surrounding whitespace or control characters.");
        return value;
    }
    private ApiException unavailable() { return new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_API_KEY","API key không còn khả dụng."); }
    private String hash(String value) { return HexFormat.of().formatHex(CredentialHasher.sha256(value)); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("API context cannot be serialized.", exception); }
    }
}
