package press.mizhifei.dentist.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import press.mizhifei.dentist.auth.model.AuthAuditOutbox;

import java.time.LocalDateTime;
import java.util.List;

public interface AuthAuditOutboxRepository extends JpaRepository<AuthAuditOutbox, Long> {

    /**
     * Claims a FIFO batch of deliverable rows, locking them against
     * concurrent relays. There is a single consumer today, but the claim
     * stays correct if a second gateway-of-record replica ever runs:
     * {@code for update skip locked} makes competing claims disjoint.
     * Rows never attempted are immediately eligible (null
     * {@code last_attempt_at}); retried rows must have cooled down past
     * {@code retryBefore}. Native query because JPQL has no portable
     * {@code SKIP LOCKED}.
     */
    @Query(value = """
            select * from auth_audit_outbox
            where last_attempt_at is null or last_attempt_at <= :retryBefore
            order by created_at asc, id asc
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<AuthAuditOutbox> claimPending(
            @Param("retryBefore") LocalDateTime retryBefore,
            @Param("batchSize") int batchSize);
}
