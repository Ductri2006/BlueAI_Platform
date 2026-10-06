package com.blueai.studio.repository;

import com.blueai.studio.model.BotTurn;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface BotTurnRepository extends JpaRepository<BotTurn, String> {
    Optional<BotTurn> findBySession_IdAndRequestId(String sessionId, String requestId);

    @Modifying
    @Query("delete from BotTurn turn where turn.session.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
