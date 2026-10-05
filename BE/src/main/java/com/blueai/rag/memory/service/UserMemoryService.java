package com.blueai.rag.memory.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.memory.dto.UpsertUserMemoryRequest;
import com.blueai.rag.memory.dto.UserMemoryResponse;
import com.blueai.rag.memory.model.MemoryScope;
import com.blueai.rag.memory.model.MemoryStatus;
import com.blueai.rag.memory.model.UserMemory;
import com.blueai.rag.memory.repository.UserMemoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.time.Instant;

@Service
public class UserMemoryService {

    private final UserRepository userRepository;
    private final UserMemoryRepository memoryRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;

    public UserMemoryService(
            UserRepository userRepository,
            UserMemoryRepository memoryRepository,
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository
    ) {
        this.userRepository = userRepository;
        this.memoryRepository = memoryRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public UserMemoryResponse upsert(String ownerId, String rawKey, UpsertUserMemoryRequest request) {
        User owner = requireActiveUser(ownerId);
        String key = normalizeKey(rawKey);
        String content = request.content().trim();
        MemoryScope scope = request.scope() == null ? MemoryScope.USER : request.scope();
        MemoryStatus status = request.status() == null ? MemoryStatus.CONFIRMED : request.status();
        if (status == MemoryStatus.REPLACED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEMORY_STATUS", "A memory cannot be created in REPLACED status.");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEMORY_EXPIRY", "expiresAt must be in the future.");
        }

        Conversation conversation = resolveConversation(ownerId, scope, request.conversationId());
        ConversationMessage sourceMessage = resolveSourceMessage(ownerId, request.sourceMessageId());
        if (scope == MemoryScope.CONVERSATION && sourceMessage != null
                && !sourceMessage.getConversation().getId().equals(conversation.getId())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_SOURCE_SCOPE_MISMATCH",
                    "The source message must belong to the scoped conversation."
            );
        }

        String replacesMemoryKey = normalizeOptionalKey(request.replacesMemoryKey());
        if (key.equals(replacesMemoryKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEMORY_REPLACEMENT", "A memory cannot replace itself.");
        }

        UserMemory memory = memoryRepository.findByOwner_IdAndMemoryKey(ownerId, key)
                .map(existing -> {
                    existing.update(content, scope, conversation, sourceMessage, status, request.expiresAt());
                    return existing;
                })
                .orElseGet(() -> new UserMemory(
                        owner,
                        key,
                        content,
                        scope,
                        conversation,
                        sourceMessage,
                        status,
                        request.expiresAt()
                ));
        if (replacesMemoryKey != null) {
            UserMemory replaced = memoryRepository.findByOwner_IdAndMemoryKey(ownerId, replacesMemoryKey)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory to replace was not found."));
            replaced.markReplacedBy(key);
            memoryRepository.save(replaced);
        }
        return UserMemoryResponse.from(memoryRepository.saveAndFlush(memory));
    }

    @Transactional(readOnly = true)
    public List<UserMemoryResponse> list(String ownerId) {
        requireActiveUser(ownerId);
        return memoryRepository.findByOwner_IdOrderByUpdatedAtDesc(ownerId)
                .stream()
                .map(UserMemoryResponse::from)
                .toList();
    }

    @Transactional
    public void delete(String ownerId, String rawKey) {
        requireActiveUser(ownerId);
        String key = normalizeKey(rawKey);
        UserMemory memory = memoryRepository.findByOwner_IdAndMemoryKey(ownerId, key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory was not found."));
        memoryRepository.delete(memory);
    }

    private User requireActiveUser(String ownerId) {
        User user = userRepository.findById(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User no longer exists."));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "User account is disabled.");
        }
        return user;
    }

    private String normalizeKey(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim().toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9][a-z0-9._-]{0,99}")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_MEMORY_KEY",
                    "Memory key must use lowercase letters, digits, dots, underscores, or hyphens."
            );
        }
        return key;
    }

    private String normalizeOptionalKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        return normalizeKey(rawKey);
    }

    private Conversation resolveConversation(String ownerId, MemoryScope scope, String conversationId) {
        if (scope == MemoryScope.USER) {
            if (conversationId != null && !conversationId.isBlank()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "MEMORY_SCOPE_CONVERSATION_UNEXPECTED",
                        "conversationId is only valid for CONVERSATION-scoped memory."
                );
            }
            return null;
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_SCOPE_CONVERSATION_REQUIRED",
                    "conversationId is required for CONVERSATION-scoped memory."
            );
        }
        return conversationRepository.findByIdAndOwner_Id(conversationId, ownerId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND",
                        "Conversation was not found."
                ));
    }

    private ConversationMessage resolveSourceMessage(String ownerId, String sourceMessageId) {
        if (sourceMessageId == null || sourceMessageId.isBlank()) return null;
        return messageRepository.findByIdAndConversation_Owner_Id(sourceMessageId, ownerId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MEMORY_SOURCE_MESSAGE_NOT_FOUND",
                        "Source message was not found."
                ));
    }
}
