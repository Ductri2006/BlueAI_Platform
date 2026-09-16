package com.blueai.studio.repository;

import com.blueai.studio.model.Bot;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface BotRepository extends JpaRepository<Bot, String> {
    List<Bot> findByOwner_IdOrderByUpdatedAtDesc(String ownerId);
    Optional<Bot> findByIdAndOwner_Id(String id, String ownerId);
    Optional<Bot> findByPublicKey(String publicKey);
    List<Bot> findByActiveTrueOrderByUpdatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Bot> findAllByOrderByUpdatedAtDesc();
}
