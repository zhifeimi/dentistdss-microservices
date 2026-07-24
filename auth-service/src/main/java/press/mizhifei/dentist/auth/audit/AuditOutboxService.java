package press.mizhifei.dentist.auth.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.auth.client.AuditClient;
import press.mizhifei.dentist.auth.dto.AuditEntryRequest;
import press.mizhifei.dentist.auth.model.AuthAuditOutbox;
import press.mizhifei.dentist.auth.repository.AuthAuditOutboxRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Durable transactional outbox for audit events (AUDIT-01), replacing the
 * former lossy executor-backed emitter.
 *
 * <p>{@link #publish} writes a pending row in the caller's transaction
 * ({@link Propagation#REQUIRED} joins the six transactional call sites and
 * opens a fresh transaction for logout, which has none), so an event exists
 * if and only if the mutation it records committed. There is deliberately no
 * immediate send: a scheduled relay ({@link #relayPendingEvents}) is the
 * single dispatch path. A row is deleted only after audit-service confirms
 * the ingestion; a failed delivery increments the attempt counters and stays
 * queued for retry with backoff. Rows are never expired or purged —
 * security evidence is never discarded, and delivery is at-least-once:
 * a crash between the confirmed call and the delete re-delivers the event
 * (audit-service stores the duplicate as a distinct document; there are no
 * idempotency keys by contract decision).
 *
 * <p>Because the write shares the caller's transaction, a database failure
 * in {@link #publish} propagates to the caller. That coupling is
 * intentional: at the in-transaction sites the business operation would fail
 * on the same database anyway, and losing the event silently with the
 * mutation would be worse than failing both together.
 *
 * <p>Future latency upgrade path (not implemented): an
 * {@code afterCommit} fast path could attempt immediate delivery once the
 * outbox row commits, leaving the relay as the durability backstop. It is
 * omitted today to keep a single dispatch code path and avoid request-path
 * latency coupling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditOutboxService {

    private static final TypeReference<Map<String, Object>> CONTEXT_TYPE = new TypeReference<>() {
    };

    private final AuthAuditOutboxRepository outboxRepository;
    private final AuditClient auditClient;
    private final ObjectMapper objectMapper;

    @Value("${app.security.audit-outbox.enabled:true}")
    private boolean relayEnabled;

    @Value("${app.security.audit-outbox.retry-backoff-ms:30000}")
    private long retryBackoffMs;

    @Value("${app.security.audit-outbox.relay-batch-size:100}")
    private int relayBatchSize;

    @Value("${app.security.audit-outbox.stale-warn-threshold:20}")
    private int staleWarnThreshold;

    /**
     * Records an audit event as a pending outbox row in the caller's
     * transaction. Delivery happens asynchronously via
     * {@link #relayPendingEvents()}.
     *
     * @param action           event action, e.g. {@code LOGIN_SUCCESS}
     * @param target           event target, e.g. {@code user:42}
     * @param assertedUserId   caller-claimed user the event concerns (nullable)
     * @param assertedClinicId caller-claimed clinic the event concerns (nullable)
     * @param context          additional event context (nullable)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void publish(
            String action,
            String target,
            Long assertedUserId,
            Long assertedClinicId,
            Map<String, Object> context) {
        AuthAuditOutbox row = AuthAuditOutbox.builder()
                .action(action)
                .target(target)
                .assertedUserId(assertedUserId)
                .assertedClinicId(assertedClinicId)
                .context(context == null ? null : objectMapper.valueToTree(context))
                .createdAt(LocalDateTime.now())
                .attempts(0)
                .build();
        outboxRepository.save(row);
    }

    /**
     * Delivers pending audit events to audit-service. The claim and the
     * per-row deletes share this transaction so the {@code for update skip
     * locked} claim and its cleanup stay atomic per batch. Never discards:
     * a failed delivery records the attempt and leaves the row queued.
     */
    @Scheduled(
            fixedDelayString = "${app.security.audit-outbox.relay-delay-ms:5000}",
            initialDelayString = "${app.security.audit-outbox.relay-initial-delay-ms:10000}")
    @Transactional
    public void relayPendingEvents() {
        if (!relayEnabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<AuthAuditOutbox> batch;
        try {
            batch = outboxRepository.claimPending(
                    now.minus(Duration.ofMillis(retryBackoffMs)),
                    relayBatchSize);
        } catch (DataAccessException ex) {
            log.error("Audit outbox relay could not claim pending rows; retrying next cycle", ex);
            return;
        }
        for (AuthAuditOutbox row : batch) {
            try {
                auditClient.record(toRequest(row));
                outboxRepository.delete(row);
            } catch (RuntimeException ex) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastAttemptAt(now);
                row.setLastError(abbreviate(ex.getMessage()));
                try {
                    outboxRepository.save(row);
                } catch (DataAccessException saveFailure) {
                    log.error("Audit outbox relay could not record the failed attempt for row {}",
                            row.getId(), saveFailure);
                }
                if (row.getAttempts() % staleWarnThreshold == 0) {
                    log.warn("Audit event '{}' for {} has failed {} deliveries; row {} remains queued",
                            row.getAction(), row.getTarget(), row.getAttempts(), row.getId());
                }
            }
        }
    }

    private AuditEntryRequest toRequest(AuthAuditOutbox row) {
        return AuditEntryRequest.builder()
                .action(row.getAction())
                .target(row.getTarget())
                .assertedUserId(row.getAssertedUserId())
                .assertedClinicId(row.getAssertedClinicId())
                .context(row.getContext() == null
                        ? null
                        : objectMapper.convertValue(row.getContext(), CONTEXT_TYPE))
                .build();
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
