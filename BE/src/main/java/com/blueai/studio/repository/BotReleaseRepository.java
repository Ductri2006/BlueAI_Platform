package com.blueai.studio.repository;

import com.blueai.studio.model.BotRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BotReleaseRepository extends JpaRepository<BotRelease, String> {
    Optional<BotRelease> findByIdAndBot_Id(String id, String botId);
    Optional<BotRelease> findByBot_IdAndRevision(String botId, long revision);
}
