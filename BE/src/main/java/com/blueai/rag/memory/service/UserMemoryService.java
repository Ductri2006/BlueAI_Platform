package com.blueai.rag.memory.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.memory.dto.UpsertUserMemoryRequest;
import com.blueai.rag.memory.dto.UserMemoryResponse;
import com.blueai.rag.memory.model.UserMemory;
import com.blueai.rag.memory.repository.UserMemoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class UserMemoryService {

    private final UserRepository userRepository;
    private final UserMemoryRepository memoryRepository;

    public UserMemoryService(UserRepository userRepository, UserMemoryRepository memoryRepository) {
        this.userRepository = userRepository;
        this.memoryRepository = memoryRepository;
    }

    @Transactional
    public UserMemoryResponse upsert(String ownerId, String rawKey, UpsertUserMemoryRequest request) {
        User owner = requireActiveUser(ownerId);
        String key = normalizeKey(rawKey);
        String content = request.content().trim();
        UserMemory memory = memoryRepository.findByOwner_IdAndMemoryKey(ownerId, key)
                .map(existing -> {
                    existing.updateContent(content);
                    return existing;
                })
                .orElseGet(() -> new UserMemory(owner, key, content));
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
}
