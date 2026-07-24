package press.mizhifei.dentist.auth.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Durable audit event publisher. Security-relevant events in this service
 * (login, logout, session-family revocation on token reuse, registration,
 * approval decisions) are written to a transactional outbox
 * ({@link AuditOutboxService}) in the caller's transaction and delivered to
 * audit-service by a scheduled relay carrying an audience-scoped service
 * credential.
 *
 * <p>Delivery is at-least-once and transaction-coupled: an event is
 * recorded if and only if the mutation it documents committed, and it is
 * retried with backoff until audit-service confirms ingestion — never
 * discarded. The entry's actor is still attributed server-side by
 * audit-service from the verified credential subject
 * ({@code auth-service}); callers of {@link #publish} can only assert
 * contextual user/clinic ids, never an actor.</p>
 *
 * <p>Caveats, by contract decision: a crash between the confirmed delivery
 * and the outbox delete re-delivers the event, so duplicate documents can
 * appear in audit-service (there are no idempotency keys); and because the
 * outbox write shares the caller's transaction, a database failure here now
 * propagates to the caller rather than being swallowed. That coupling is
 * intentional — at the in-transaction call sites the business operation
 * would fail on the same database anyway, and losing the event silently
 * with the mutation would be worse than failing both together.</p>
 */
@Component
@RequiredArgsConstructor
public class AuditEventPublisher {

    private final AuditOutboxService auditOutboxService;

    /**
     * Records an audit event for durable, relay-delivered emission.
     *
     * @param action           event action, e.g. {@code LOGIN_SUCCESS}
     * @param target           event target, e.g. {@code user:42}
     * @param assertedUserId   caller-claimed user the event concerns (nullable)
     * @param assertedClinicId caller-claimed clinic the event concerns (nullable)
     * @param context          additional event context (nullable)
     */
    public void publish(
            String action,
            String target,
            Long assertedUserId,
            Long assertedClinicId,
            Map<String, Object> context) {
        auditOutboxService.publish(action, target, assertedUserId, assertedClinicId, context);
    }
}
