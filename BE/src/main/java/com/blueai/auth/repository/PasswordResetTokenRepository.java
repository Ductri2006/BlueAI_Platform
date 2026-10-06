package com.blueai.auth.repository;

import com.blueai.auth.model.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PasswordResetToken token where token.user.id = :userId and token.usedAt is null")
    int revokeActiveForUser(@Param("userId") String userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PasswordResetToken token where token.expiresAt < :cutoff or token.usedAt < :cutoff")
    int purgeBefore(@Param("cutoff") Instant cutoff);
}
