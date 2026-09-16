package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.studio.dto.BotStudioDtos.CreateWidgetSessionResponse;
import com.blueai.studio.dto.BotStudioDtos.PreviewContext;
import com.blueai.studio.dto.BotStudioDtos.PublicRuntimeOutput;
import com.blueai.studio.dto.BotStudioDtos.RuntimeMessageRequest;
import com.blueai.studio.dto.BotStudioDtos.RuntimeMessageResponse;
import com.blueai.studio.dto.BotStudioDtos.RuntimeResponse;
import com.blueai.studio.model.Bot;
import com.blueai.studio.model.BotRelease;
import com.blueai.studio.model.BotSession;
import com.blueai.studio.model.BotStatus;
import com.blueai.studio.model.BotTurn;
import com.blueai.studio.repository.BotReleaseRepository;
import com.blueai.studio.repository.BotRepository;
import com.blueai.studio.repository.BotSessionRepository;
import com.blueai.studio.repository.BotTurnRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class BotRuntimeService {

    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_SESSION_CREATIONS_PER_MINUTE = 20;
    private static final int MAX_MESSAGES_PER_MINUTE_PER_CLIENT = 60;
    private static final int MAX_MESSAGES_PER_MINUTE_PER_SESSION = 30;
    private static final int MAX_MESSAGES_PER_SESSION = 200;

    private final BotRepository botRepository;
    private final BotReleaseRepository releaseRepository;
    private final BotSessionRepository sessionRepository;
    private final BotTurnRepository turnRepository;
    private final DialogRuntimeService dialogRuntimeService;
    private final WidgetRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public BotRuntimeService(
            BotRepository botRepository,
            BotReleaseRepository releaseRepository,
            BotSessionRepository sessionRepository,
            BotTurnRepository turnRepository,
            DialogRuntimeService dialogRuntimeService,
            WidgetRateLimiter rateLimiter,
            ObjectMapper objectMapper
    ) {
        this.botRepository = botRepository;
        this.releaseRepository = releaseRepository;
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.dialogRuntimeService = dialogRuntimeService;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreateWidgetSessionResponse createSession(String publicKey, String clientAddress) {
        rateLimiter.check(
                "session:" + safeClientAddress(clientAddress),
                MAX_SESSION_CREATIONS_PER_MINUTE,
                RATE_LIMIT_WINDOW,
                "Quá nhiều phiên widget được tạo từ địa chỉ này. Hãy thử lại sau."
        );
        Bot bot = botRepository.findByPublicKey(publicKey)
                .filter(item -> item.getStatus() == BotStatus.PUBLISHED && item.getPublishedReleaseId() != null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOT_UNAVAILABLE", "Chatbot chưa được xuất bản."));
        BotRelease release = requireRelease(bot, bot.getPublishedReleaseId());
        BotSnapshot snapshot = readJson(release.getConfigurationSnapshot(), BotSnapshot.class);
        String accessToken = UUID.randomUUID() + "." + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(SESSION_TTL);
        RuntimeResponse welcome = dialogRuntimeService.welcome(snapshot);
        BotSession session = sessionRepository.saveAndFlush(new BotSession(
                bot,
                release.getId(),
                sha256(accessToken),
                writeJson(welcome.context()),
                expiresAt
        ));
        return new CreateWidgetSessionResponse(
                session.getId(),
                accessToken,
                release.getId(),
                release.getRevision(),
                PublicRuntimeOutput.from(welcome),
                expiresAt
        );
    }

    @Transactional
    public RuntimeMessageResponse message(
            String sessionId,
            String accessToken,
            RuntimeMessageRequest request,
            String clientAddress
    ) {
        BotSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Không tìm thấy phiên chatbot."));
        requireValidToken(session, accessToken);
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "SESSION_EXPIRED", "Phiên chatbot đã hết hạn. Hãy bắt đầu phiên mới.");
        }

        rateLimiter.check(
                "message-client:" + safeClientAddress(clientAddress),
                MAX_MESSAGES_PER_MINUTE_PER_CLIENT,
                RATE_LIMIT_WINDOW,
                "Quá nhiều tin nhắn widget từ địa chỉ này. Hãy thử lại sau."
        );
        rateLimiter.check(
                "message-session:" + sessionId,
                MAX_MESSAGES_PER_MINUTE_PER_SESSION,
                RATE_LIMIT_WINDOW,
                "Phiên chatbot đang gửi tin nhắn quá nhanh. Hãy thử lại sau."
        );

        String trimmedMessage = request.message().trim();
        String digest = sha256(trimmedMessage);
        BotTurn existing = turnRepository.findBySession_IdAndRequestId(sessionId, request.requestId()).orElse(null);
        if (existing != null) {
            if (!MessageDigest.isEqual(
                    existing.getRequestDigest().getBytes(StandardCharsets.US_ASCII),
                    digest.getBytes(StandardCharsets.US_ASCII)
            )) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "requestId đã được dùng với một nội dung khác."
                );
            }
            return readJson(existing.getResponseJson(), RuntimeMessageResponse.class);
        }
        if (session.getTurnCount() >= MAX_MESSAGES_PER_SESSION) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "SESSION_TURN_LIMIT",
                    "Phiên chatbot đã đạt giới hạn lượt hội thoại. Hãy bắt đầu phiên mới."
            );
        }

        BotRelease release = requireRelease(session.getBot(), session.getReleaseId());
        BotSnapshot snapshot = readJson(release.getConfigurationSnapshot(), BotSnapshot.class);
        PreviewContext state = readJson(session.getStateJson(), PreviewContext.class);
        RuntimeResponse result = dialogRuntimeService.evaluate(snapshot, state, trimmedMessage);
        RuntimeMessageResponse response = new RuntimeMessageResponse(
                request.requestId(),
                session.getId(),
                release.getId(),
                release.getRevision(),
                PublicRuntimeOutput.from(result)
        );

        session.updateState(writeJson(result.context()));
        session.incrementTurnCount();
        sessionRepository.saveAndFlush(session);
        turnRepository.saveAndFlush(new BotTurn(session, request.requestId(), digest, writeJson(response)));
        return response;
    }

    private void requireValidToken(BotSession session, String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WIDGET_TOKEN_REQUIRED", "Thiếu widget session token.");
        }
        byte[] expected = session.getAccessTokenHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sha256(accessToken).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WIDGET_TOKEN_INVALID", "Widget session token không hợp lệ.");
        }
    }

    private BotRelease requireRelease(Bot bot, String releaseId) {
        return releaseRepository.findByIdAndBot_Id(releaseId, bot.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RELEASE_UNAVAILABLE", "Bản phát hành chatbot không khả dụng."));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RUNTIME_SERIALIZATION_FAILED", "Không thể lưu trạng thái chatbot.");
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RUNTIME_CONFIGURATION_INVALID", "Cấu hình chatbot đã lưu không hợp lệ.");
        }
    }

    private String safeClientAddress(String clientAddress) {
        return clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress;
    }
}
