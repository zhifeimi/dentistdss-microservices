package press.mizhifei.dentist.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.auth.model.AuthSecurityOutbox;
import press.mizhifei.dentist.auth.repository.AuthSecurityOutboxRepository;
import press.mizhifei.dentist.auth.repository.AuthSessionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Durable outbox for Redis session-family tombstones.
 *
 * <p>Every database revocation records a pending outbox row in the same
 * transactional boundary before the synchronous Redis publication is
 * attempted. A successful publication deletes the row; a failed one leaves it
 * for {@link #replayPendingPublications()}, which retries until the tombstone
 * exists or the family lifetime has passed. This closes the gap where a
 * revoked family kept a live Redis "active" key (and therefore valid access
 * tokens at the gateway) after a transient Redis failure.
 *
 * <p>{@link #reconcileRevokedFamilies()} additionally sweeps revoked,
 * unexpired database sessions and re-enqueues any family whose Redis state
 * key is missing, healing gaps created before the outbox existed or by
 * outbox-write failures after a committed revocation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityStateOutboxService {

    private final AuthSecurityOutboxRepository outboxRepository;
    private final AuthSessionRepository sessionRepository;
    private final SecurityStateService securityStateService;

    @Value("${app.security.outbox.enabled:true}")
    private boolean outboxEnabled;

    @Value("${app.security.outbox.retry-backoff-ms:30000}")
    private long retryBackoffMs;

    @Value("${app.security.outbox.replay-batch-size:100}")
    private int replayBatchSize;

    @Value("${app.security.outbox.sweep-enabled:true}")
    private boolean sweepEnabled;

    @Value("${app.security.outbox.sweep-batch-size:500}")
    private int sweepBatchSize;

    @Value("${app.security.refresh-token-days:30}")
    private long refreshTokenDays;

    @Value("${jwt.expiration}")
    private long accessTokenExpirationMs;

    @Value("${jwt.clock-skew-seconds:60}")
    private long jwtClockSkewSeconds;

    /**
     * Records a pending tombstone publication in the caller's transaction, so
     * the marker commits exactly when the database revocation does.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void enqueue(long userId, String familyId, LocalDateTime createdAt, Duration tombstoneTtl) {
        upsert(userId, familyId, createdAt, tombstoneTtl);
    }

    /**
     * Records a pending tombstone publication in its own transaction, for
     * revocation paths that already committed the database change.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueStandalone(long userId, String familyId, LocalDateTime createdAt, Duration tombstoneTtl) {
        upsert(userId, familyId, createdAt, tombstoneTtl);
    }

    /** Drops the pending marker after a successful synchronous publication. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void markPublished(long userId, String familyId) {
        outboxRepository.deleteByUserIdAndFamilyId(userId, familyId);
    }

    @Scheduled(
            fixedDelayString = "${app.security.outbox.replay-delay-ms:30000}",
            initialDelayString = "${app.security.outbox.replay-initial-delay-ms:15000}")
    public void replayPendingPublications() {
        if (!outboxEnabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<AuthSecurityOutbox> batch;
        try {
            batch = outboxRepository.findPending(
                    now,
                    now.minus(Duration.ofMillis(retryBackoffMs)),
                    Limit.of(replayBatchSize));
        } catch (DataAccessException ex) {
            log.error("Security outbox replay could not read pending rows; retrying next cycle", ex);
            return;
        }
        for (AuthSecurityOutbox row : batch) {
            try {
                securityStateService.revokeFamily(
                        row.getUserId(),
                        row.getFamilyId(),
                        Duration.ofMillis(row.getTombstoneTtlMillis()));
                outboxRepository.delete(row);
            } catch (RuntimeException ex) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastAttemptAt(now);
                row.setLastError(abbreviate(ex.getMessage()));
                try {
                    outboxRepository.save(row);
                } catch (DataAccessException saveFailure) {
                    log.error("Security outbox replay could not record the failed attempt for user {} family {}",
                            row.getUserId(), row.getFamilyId(), saveFailure);
                }
            }
        }
        try {
            int purged = outboxRepository.deleteExpired(now);
            if (purged > 0) {
                log.info("Purged {} expired security outbox rows", purged);
            }
        } catch (DataAccessException ex) {
            log.error("Security outbox replay could not purge expired rows", ex);
        }
    }

    @Scheduled(
            fixedDelayString = "${app.security.outbox.sweep-delay-ms:300000}",
            initialDelayString = "${app.security.outbox.sweep-initial-delay-ms:60000}")
    public void reconcileRevokedFamilies() {
        if (!outboxEnabled || !sweepEnabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            List<Object[]> revokedFamilies = sessionRepository.findDistinctRevokedUnexpiredFamilies(
                    now,
                    Limit.of(sweepBatchSize));
            if (revokedFamilies.isEmpty()) {
                return;
            }
            List<SecurityStateService.FamilyRef> familyRefs = revokedFamilies.stream()
                    .map(row -> new SecurityStateService.FamilyRef(
                            ((Number) row[0]).longValue(),
                            (String) row[1]))
                    .toList();
            List<SecurityStateService.FamilyRef> missing = securityStateService.findFamiliesWithoutState(familyRefs);
            Duration tombstoneTtl = familyLifetime();
            for (SecurityStateService.FamilyRef family : missing) {
                enqueueStandalone(family.userId(), family.familyId(), now, tombstoneTtl);
            }
            if (!missing.isEmpty()) {
                log.warn("Re-enqueued {} revoked session families missing Redis tombstones", missing.size());
            }
        } catch (RuntimeException ex) {
            log.error("Security outbox reconciliation failed; retrying next sweep", ex);
        }
    }

    private void upsert(long userId, String familyId, LocalDateTime createdAt, Duration tombstoneTtl) {
        outboxRepository.upsertPending(
                userId,
                familyId,
                tombstoneTtl.toMillis(),
                createdAt,
                createdAt.plus(tombstoneTtl));
    }

    private Duration familyLifetime() {
        return Duration.ofDays(refreshTokenDays)
                .plusMillis(accessTokenExpirationMs)
                .plusSeconds(jwtClockSkewSeconds);
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
