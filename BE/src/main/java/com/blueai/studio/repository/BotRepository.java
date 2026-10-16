package com.blueai.studio.repository;

import com.blueai.studio.model.Bot;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BotRepository extends JpaRepository<Bot, String> {
    @Query("""
            select bot from Bot bot
            where bot.owner.id = :actorId or bot.owner.role = com.blueai.auth.model.UserRole.ADMIN
            order by bot.updatedAt desc, bot.id asc
            """)
    List<Bot> findAdminWorkspaceBots(@Param("actorId") String actorId);

    @Query("""
            select bot from Bot bot where bot.id = :botId
              and (bot.owner.id = :actorId or bot.owner.role = com.blueai.auth.model.UserRole.ADMIN)
            """)
    Optional<Bot> findAdminWorkspaceBot(@Param("botId") String botId, @Param("actorId") String actorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bot from Bot bot where bot.id = :botId
              and (bot.owner.id = :actorId or bot.owner.role = com.blueai.auth.model.UserRole.ADMIN)
            """)
    Optional<Bot> findAdminWorkspaceBotForUpdate(@Param("botId") String botId, @Param("actorId") String actorId);

    // A fixed lock order serializes global activation by different admins.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bot from Bot bot order by bot.id asc")
    List<Bot> findAllForActivation();

    List<Bot> findByOwner_IdOrderByUpdatedAtDesc(String ownerId);
    Optional<Bot> findByIdAndOwner_Id(String id, String ownerId);
    Optional<Bot> findByPublicKey(String publicKey);
    List<Bot> findByActiveTrueOrderByUpdatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Bot> findAllByOrderByUpdatedAtDesc();
}
