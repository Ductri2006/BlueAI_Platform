package com.blueai.rag.conversation.repository;

import com.blueai.rag.conversation.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findByOwner_IdOrderByUpdatedAtDesc(String ownerId);

    List<Conversation> findTop50ByOwner_IdOrderByUpdatedAtDesc(String ownerId);

    Optional<Conversation> findByIdAndOwner_Id(String conversationId, String ownerId);
}
