package press.mizhifei.dentist.auth.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import press.mizhifei.dentist.auth.model.AuthSecurityOutbox;

import java.time.LocalDateTime;
import java.util.List;

public interface AuthSecurityOutboxRepository extends JpaRepository<AuthSecurityOutbox, Long> {

    @Modifying
    @Query(value = """
            insert into auth_security_outbox (user_id, family_id, tombstone_ttl_millis, created_at, attempts, expires_at)
            values (:userId, :familyId, :tombstoneTtlMillis, :createdAt, 0, :expiresAt)
            on conflict (user_id, family_id) do update set
                expires_at = excluded.expires_at,
                tombstone_ttl_millis = excluded.tombstone_ttl_millis
            """, nativeQuery = true)
    int upsertPending(
            @Param("userId") long userId,
            @Param("familyId") String familyId,
            @Param("tombstoneTtlMillis") long tombstoneTtlMillis,
            @Param("createdAt") LocalDateTime createdAt,
            @Param("expiresAt") LocalDateTime expiresAt);

    @Query("""
            select outbox from AuthSecurityOutbox outbox
            where outbox.expiresAt > :now
              and (outbox.lastAttemptAt is null or outbox.lastAttemptAt < :attemptedBefore)
            order by outbox.createdAt asc
            """)
    List<AuthSecurityOutbox> findPending(
            @Param("now") LocalDateTime now,
            @Param("attemptedBefore") LocalDateTime attemptedBefore,
            Limit limit);

    int deleteByUserIdAndFamilyId(long userId, String familyId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AuthSecurityOutbox outbox where outbox.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
