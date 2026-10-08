package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.rag.chat.service.RagChatService;
import com.blueai.rag.chat.service.LlmChatMessage;
import com.blueai.studio.config.WidgetProtectionProperties;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Service
public class BotRuntimeService {

    private final BotRepository botRepository;
    private final BotReleaseRepository releaseRepository;
    private final BotSessionRepository sessionRepository;
    private final BotTurnRepository turnRepository;
    private final DialogRuntimeService dialogRuntimeService;
    private final WidgetRateLimiter rateLimiter;
    private final WidgetConversationContextStore contextStore;
    private final WidgetSessionRequestCoordinator requestCoordinator;
    private final WidgetProtectionProperties protectionProperties;
    private final WidgetProtectionMetrics protectionMetrics;
    private final ObjectMapper objectMapper;
    private final RagChatService ragChatService;

    public BotRuntimeService(
            BotRepository botRepository,
            BotReleaseRepository releaseRepository,
            BotSessionRepository sessionRepository,
            BotTurnRepository turnRepository,
            DialogRuntimeService dialogRuntimeService,
            WidgetRateLimiter rateLimiter,
            WidgetConversationContextStore contextStore,
            WidgetSessionRequestCoordinator requestCoordinator,
            WidgetProtectionProperties protectionProperties,
            WidgetProtectionMetrics protectionMetrics,
            ObjectMapper objectMapper,
            RagChatService ragChatService
    ) {
        this.botRepository = botRepository;
        this.releaseRepository = releaseRepository;
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.dialogRuntimeService = dialogRuntimeService;
        this.rateLimiter = rateLimiter;
        this.contextStore = contextStore;
        this.requestCoordinator = requestCoordinator;
        this.protectionProperties = protectionProperties;
        this.protectionMetrics = protectionMetrics;
        this.objectMapper = objectMapper;
        this.ragChatService = ragChatService;
    }

    @Transactional
    public CreateWidgetSessionResponse createSession(String publicKey, String clientAddress) {
        rateLimiter.check(
                "session:" + rateLimiter.clientKey(clientAddress),
                protectionProperties.getMaxSessionCreationsPerWindow(),
                protectionProperties.getRateLimitWindow(),
                "Quá nhiều phiên widget được tạo từ địa chỉ này. Hãy thử lại sau."
        );
        Bot bot = botRepository.findByPublicKey(publicKey)
                .filter(item -> item.getStatus() == BotStatus.PUBLISHED && item.getPublishedReleaseId() != null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOT_UNAVAILABLE", "Chatbot chưa được xuất bản."));
        BotRelease release = requireRelease(bot, bot.getPublishedReleaseId());
        BotSnapshot snapshot = readJson(release.getConfigurationSnapshot(), BotSnapshot.class);
        String accessToken = UUID.randomUUID() + "." + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(protectionProperties.getSessionTtl());
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

        String trimmedMessage = request.message().trim();
        String digest = sha256(trimmedMessage);
        // A network retry must not consume a new rate-limit token or invoke the
        // LLM again. requestId is the widget's idempotency key.
        BotTurn existingTurn = turnRepository.findBySession_IdAndRequestId(sessionId, request.requestId()).orElse(null);
        if (existingTurn != null) {
            if (!MessageDigest.isEqual(
                    existingTurn.getRequestDigest().getBytes(StandardCharsets.US_ASCII),
                    digest.getBytes(StandardCharsets.US_ASCII)
            )) {
                protectionMetrics.record(
                        WidgetProtectionMetrics.Event.IDEMPOTENCY_CONFLICT,
                        WidgetProtectionMetrics.Scope.MESSAGE
                );
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "requestId has already been used with different content.");
            }
            protectionMetrics.record(
                    WidgetProtectionMetrics.Event.IDEMPOTENCY_REPLAY,
                    WidgetProtectionMetrics.Scope.MESSAGE
            );
            return readJson(existingTurn.getResponseJson(), RuntimeMessageResponse.class);
        }

        rateLimiter.check(
                "message-client:" + rateLimiter.clientKey(clientAddress),
                protectionProperties.getMaxMessagesPerWindowPerClient(),
                protectionProperties.getRateLimitWindow(),
                "Quá nhiều tin nhắn widget từ địa chỉ này. Hãy thử lại sau."
        );
        rateLimiter.check(
                "message-session:" + sessionId,
                protectionProperties.getMaxMessagesPerWindowPerSession(),
                protectionProperties.getRateLimitWindow(),
                "Phiên chatbot đang gửi tin nhắn quá nhanh. Hãy thử lại sau."
        );

        // Catch a request that completed in another instance between the first
        // idempotency lookup and the shared rate-limit check.
        BotTurn raceCompleted = turnRepository.findBySession_IdAndRequestId(sessionId, request.requestId()).orElse(null);
        if (raceCompleted != null) {
            if (!MessageDigest.isEqual(
                    raceCompleted.getRequestDigest().getBytes(StandardCharsets.US_ASCII),
                    digest.getBytes(StandardCharsets.US_ASCII)
            )) {
                protectionMetrics.record(
                        WidgetProtectionMetrics.Event.IDEMPOTENCY_CONFLICT,
                        WidgetProtectionMetrics.Scope.MESSAGE
                );
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "requestId đã được dùng với một nội dung khác."
                );
            }
            protectionMetrics.record(
                    WidgetProtectionMetrics.Event.IDEMPOTENCY_REPLAY,
                    WidgetProtectionMetrics.Scope.MESSAGE
            );
            return readJson(raceCompleted.getResponseJson(), RuntimeMessageResponse.class);
        }
        WidgetConversationContextStore.DuplicateResult duplicate = contextStore.recordDuplicateAttempt(sessionId, digest);
        if (duplicate.isDuplicate()) {
            if (duplicate.repeatCount() > protectionProperties.getDuplicateReplayLimit()) {
                protectionMetrics.record(
                        WidgetProtectionMetrics.Event.DUPLICATE_BLOCKED,
                        WidgetProtectionMetrics.Scope.MESSAGE
                );
                throw new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "WIDGET_DUPLICATE_MESSAGE",
                        "The same message was submitted repeatedly. Please wait or rephrase your question.",
                        protectionProperties.getDuplicateWindow().toSeconds()
                );
            }
            RuntimeMessageResponse replayed = replayResponse(
                    request.requestId(),
                    readJson(duplicate.responseJson(), RuntimeMessageResponse.class)
            );
            protectionMetrics.record(
                    WidgetProtectionMetrics.Event.DUPLICATE_REPLAY,
                    WidgetProtectionMetrics.Scope.MESSAGE
            );
            // Persist the fresh requestId too. Redis is deliberately ephemeral;
            // this keeps an uncertain network retry idempotent after its cache
            // entry has expired without treating the replay as a new AI turn.
            turnRepository.saveAndFlush(new BotTurn(session, request.requestId(), digest, writeJson(replayed)));
            return replayed;
        }

        if (session.getTurnCount() >= protectionProperties.getMaxMessagesPerSession()) {
            protectionMetrics.record(
                    WidgetProtectionMetrics.Event.SESSION_TURN_LIMIT,
                    WidgetProtectionMetrics.Scope.MESSAGE
            );
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "SESSION_TURN_LIMIT",
                    "Phiên chatbot đã đạt giới hạn lượt hội thoại. Hãy bắt đầu phiên mới."
            );
        }

        try (WidgetSessionRequestCoordinator.LockHandle lock = requestCoordinator.tryAcquire(sessionId)) {
            if (!lock.acquired()) {
                BotTurn completedTurn = turnRepository.findBySession_IdAndRequestId(sessionId, request.requestId()).orElse(null);
                if (completedTurn != null && MessageDigest.isEqual(
                        completedTurn.getRequestDigest().getBytes(StandardCharsets.US_ASCII),
                        digest.getBytes(StandardCharsets.US_ASCII)
                )) {
                    protectionMetrics.record(
                            WidgetProtectionMetrics.Event.IDEMPOTENCY_REPLAY,
                            WidgetProtectionMetrics.Scope.MESSAGE
                    );
                    return readJson(completedTurn.getResponseJson(), RuntimeMessageResponse.class);
                }
                protectionMetrics.record(
                        WidgetProtectionMetrics.Event.REQUEST_IN_PROGRESS,
                        WidgetProtectionMetrics.Scope.MESSAGE
                );
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "WIDGET_REQUEST_IN_PROGRESS",
                        "Another message for this chat session is still being processed. Retry with the same requestId."
                );
            }

            BotRelease release = requireRelease(session.getBot(), session.getReleaseId());
            BotSnapshot snapshot = readJson(release.getConfigurationSnapshot(), BotSnapshot.class);
            WidgetState state = readWidgetState(session.getStateJson());
            RuntimeResponse result = dialogRuntimeService.evaluate(snapshot, state.dialogContext(), trimmedMessage);
            boolean handled = result.context().pendingEntity() != null || !result.fallback() || result.dialogHandled();
            if (!handled && !Boolean.FALSE.equals(snapshot.blueAiCoreEnabled())) {
                String answer = ragChatService.answerForWidget(snapshot, trimmedMessage, state.history());
                result = new RuntimeResponse(answer, null, Map.of(), "BlueAI Core", result.context(), false);
            }
            RuntimeMessageResponse response = new RuntimeMessageResponse(
                    request.requestId(),
                    session.getId(),
                    release.getId(),
                    release.getRevision(),
                    PublicRuntimeOutput.from(result)
            );

            String responseJson = writeJson(response);
            List<LlmChatMessage> history = new ArrayList<>(state.history());
            history.add(new LlmChatMessage("user", trimmedMessage));
            history.add(new LlmChatMessage("assistant", result.message()));
            history = history.subList(Math.max(0, history.size() - 12), history.size());
            session.updateState(writeJson(new WidgetState(result.context(), history)));
            session.incrementTurnCount();
            sessionRepository.saveAndFlush(session);
            turnRepository.saveAndFlush(new BotTurn(session, request.requestId(), digest, responseJson));
            contextStore.recordResponse(sessionId, digest, responseJson, trimmedMessage);
            return response;
        }
    }

    private record WidgetState(PreviewContext dialogContext, List<LlmChatMessage> history) {
        private WidgetState {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    private WidgetState readWidgetState(String json) {
        try {
            if (objectMapper.readTree(json).has("dialogContext")) return readJson(json, WidgetState.class);
            // Sessions created by the earlier Dialog-only runtime remain readable.
            return new WidgetState(readJson(json, PreviewContext.class), List.of());
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WIDGET_STATE_INVALID", "Không thể đọc trạng thái widget.");
        }
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

    private RuntimeMessageResponse replayResponse(String requestId, RuntimeMessageResponse original) {
        return new RuntimeMessageResponse(
                requestId,
                original.sessionId(),
                original.releaseId(),
                original.revision(),
                original.result()
        );
    }
}
