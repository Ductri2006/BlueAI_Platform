package com.blueai.rag.conversation.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.conversation.dto.ConversationResponse;
import com.blueai.rag.conversation.dto.ConversationPageResponse;
import com.blueai.rag.conversation.dto.CreateConversationRequest;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationSummaryRepository;
import com.blueai.rag.conversation.repository.ConversationLinkRepository;
import com.blueai.rag.memory.repository.UserMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.Duration;
import java.time.Instant;

@Service
public class ConversationService {

    private static final String DEFAULT_TITLE = "New conversation";

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final ConversationLinkRepository linkRepository;
    private final UserMemoryRepository memoryRepository;
    private final boolean retentionEnabled;
    private final Duration conversationRetention;
    private final int retentionBatchSize;

    public ConversationService(
            UserRepository userRepository,
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository,
            ConversationSummaryRepository summaryRepository,
            ConversationLinkRepository linkRepository,
            UserMemoryRepository memoryRepository,
            @Value("${app.retention.enabled:false}") boolean retentionEnabled,
            @Value("${app.retention.conversation:365d}") Duration conversationRetention,
            @Value("${app.retention.batch-size:100}") int retentionBatchSize
    ) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.summaryRepository = summaryRepository;
        this.linkRepository = linkRepository;
        this.memoryRepository = memoryRepository;
        this.retentionEnabled = retentionEnabled;
        this.conversationRetention = conversationRetention.isNegative() ? Duration.ZERO : conversationRetention;
        this.retentionBatchSize = Math.max(1, Math.min(100, retentionBatchSize));
    }

    @Transactional
    public ConversationResponse create(String ownerId, CreateConversationRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User no longer exists."));
        if (owner.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "User account is disabled.");
        }

        String title = request.title() == null || request.title().isBlank()
                ? DEFAULT_TITLE
                : request.title().trim();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(owner, title));
        return ConversationResponse.from(conversation);
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(String ownerId) {
        return conversationRepository.findByOwner_IdOrderByUpdatedAtDesc(ownerId)
                .stream()
                .map(ConversationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationPageResponse search(
            String ownerId,
            String query,
            com.blueai.rag.conversation.model.ConversationContextMode contextMode,
            int page,
            int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "page must be >= 0 and size must be between 1 and 100.");
        }
        Page<Conversation> result = conversationRepository.searchActive(
                ownerId,
                escapeLike(query == null ? "" : query.trim()),
                contextMode,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by("id")))
        );
        return new ConversationPageResponse(
                result.getContent().stream().map(ConversationResponse::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext(),
                result.hasPrevious()
        );
    }

    @Transactional(readOnly = true)
    public ConversationResponse get(String ownerId, String conversationId) {
        Conversation conversation = conversationRepository.findByIdAndOwner_Id(conversationId, ownerId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND",
                        "Conversation was not found."
                ));
        return ConversationResponse.from(conversation);
    }

    @Transactional
    public void delete(String ownerId, String conversationId) {
        Conversation conversation = conversationRepository.findByIdAndOwnerIncludingDeleted(conversationId, ownerId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND",
                        "Conversation was not found."
                ));
        if (conversation.isDeleted()) {
            return;
        }
        // Keep only a tombstone for retention/audit. Dependent content is
        // removed in this transaction so no old message, summary, memory, or
        // frozen link can remain available to RAG context after deletion.
        linkRepository.deleteByTargetConversation_IdOrSourceConversation_Id(conversationId, conversationId);
        memoryRepository.deleteBySourceMessage_Conversation_Id(conversationId);
        memoryRepository.deleteByConversation_Id(conversationId);
        summaryRepository.deleteByConversation_Id(conversationId);
        messageRepository.deleteByConversation_Id(conversationId);
        conversation.softDelete();
    }

    @Transactional
    public int purgeDeletedBefore(Instant cutoff, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PURGE_LIMIT", "limit must be between 1 and 100.");
        }
        List<Conversation> expired = conversationRepository.findDeletedBefore(cutoff, PageRequest.of(0, limit));
        conversationRepository.deleteAll(expired);
        return expired.size();
    }

    /**
     * Retention is deliberately disabled unless app.retention.enabled=true.
     * The explicit clock parameter keeps the policy deterministic in tests and
     * makes the deletion boundary auditable independently of scheduler timing.
     */
    @Transactional
    int runRetentionCleanup(Instant now) {
        if (!retentionEnabled) {
            return 0;
        }
        return purgeDeletedBefore(now.minus(conversationRetention), retentionBatchSize);
    }

    @Scheduled(
            fixedDelayString = "${app.retention.cleanup-interval:1h}",
            initialDelayString = "${app.retention.cleanup-interval:1h}"
    )
    @Transactional
    public void purgeExpiredConversations() {
        runRetentionCleanup(Instant.now());
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
