package press.mizhifei.dentist.auth.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import press.mizhifei.dentist.auth.model.AuthSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {
    Optional<AuthSession> findByTokenHash(String tokenHash);

    @Query("""
            select distinct authSession.familyId
            from AuthSession authSession
            where authSession.userId = :userId
              and authSession.revokedAt is null
            """)
    List<String> findDistinctUnrevokedFamilyIdsByUserId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession authSession
            set authSession.revokedAt = :revokedAt,
                authSession.replacedByHash = :replacementHash
            where authSession.tokenHash = :tokenHash
              and authSession.revokedAt is null
              and authSession.expiresAt > :now
            """)
    int rotateIfActive(
            @Param("tokenHash") String tokenHash,
            @Param("now") LocalDateTime now,
            @Param("revokedAt") LocalDateTime revokedAt,
            @Param("replacementHash") String replacementHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession authSession
            set authSession.revokedAt = :revokedAt
            where authSession.userId = :userId
              and authSession.familyId = :familyId
              and authSession.revokedAt is null
            """)
    int revokeActiveByUserIdAndFamilyId(
            @Param("userId") Long userId,
            @Param("familyId") String familyId,
            @Param("revokedAt") LocalDateTime revokedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession authSession
            set authSession.revokedAt = :revokedAt
            where authSession.userId = :userId
              and authSession.revokedAt is null
            """)
    int revokeActiveByUserId(
            @Param("userId") Long userId,
            @Param("revokedAt") LocalDateTime revokedAt);

    @Query("""
            select distinct authSession.familyId
            from AuthSession authSession
            where authSession.userId = :userId
              and authSession.expiresAt > :now
            """)
    List<String> findDistinctUnexpiredFamilyIdsByUserId(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now);

    /**
     * Distinct (userId, familyId) pairs revoked in the database whose sessions
     * have not expired yet. Used by the security outbox reconciliation sweep
     * to find revocations whose Redis tombstone is missing.
     */
    @Query("""
            select distinct authSession.userId, authSession.familyId
            from AuthSession authSession
            where authSession.revokedAt is not null
              and authSession.expiresAt > :now
            order by authSession.userId asc, authSession.familyId asc
            """)
    List<Object[]> findDistinctRevokedUnexpiredFamilies(
            @Param("now") LocalDateTime now,
            Limit limit);
}
