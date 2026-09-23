package com.blueai.rag.memory.repository;

import com.blueai.rag.memory.model.UserMemory;
import com.blueai.rag.memory.model.MemoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserMemoryRepository extends JpaRepository<UserMemory, String> {

    List<UserMemory> findByOwner_IdOrderByUpdatedAtDesc(String ownerId);

    Optional<UserMemory> findByOwner_IdAndMemoryKey(String ownerId, String memoryKey);

    List<UserMemory> findByOwner_IdAndStatusOrderByUpdatedAtDesc(String ownerId, MemoryStatus status);

    void deleteByConversation_Id(String conversationId);

    void deleteBySourceMessage_Conversation_Id(String conversationId);
}
