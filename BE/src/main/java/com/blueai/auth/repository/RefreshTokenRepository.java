package com.blueai.auth.repository;

import com.blueai.auth.model.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    // A scalar lookup does not cache a token snapshot or acquire its row lock.
    // Session mutations must lock the owner before locking any refresh token.
    @Query("select token.user.id from RefreshToken token where token.tokenHash = :tokenHash")
    Optional<String> findOwnerIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken token
               set token.revokedAt = :revokedAt
             where token.user.id = :userId
               and token.familyId = :familyId
               and token.revokedAt is null
            """)
    int revokeFamily(
            @Param("userId") String userId,
            @Param("familyId") String familyId,
            @Param("revokedAt") Instant revokedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken token
               set token.revokedAt = :revokedAt
             where token.user.id = :userId
               and token.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") String userId, @Param("revokedAt") Instant revokedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken token where (token.revokedAt is not null and token.revokedAt < :cutoff) or token.expiresAt < :cutoff")
    int purgeBefore(@Param("cutoff") Instant cutoff);
}
