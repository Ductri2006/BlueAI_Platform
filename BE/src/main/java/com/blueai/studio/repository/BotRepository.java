package com.blueai.studio.repository;

import com.blueai.studio.model.Bot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotRepository extends JpaRepository<Bot, String> {
    List<Bot> findByOwner_IdOrderByUpdatedAtDesc(String ownerId);
    Optional<Bot> findByIdAndOwner_Id(String id, String ownerId);
    Optional<Bot> findByPublicKey(String publicKey);
}
