package com.blueai.studio.repository;

import com.blueai.studio.model.BotSession;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface BotSessionRepository extends JpaRepository<BotSession, String> {

    @Modifying
    @Query("delete from BotSession session where session.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
