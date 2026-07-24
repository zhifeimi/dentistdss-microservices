package press.mizhifei.dentist.auth.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import press.mizhifei.dentist.auth.client.AuditClient;
import press.mizhifei.dentist.auth.dto.AuditEntryRequest;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Best-effort audit event publisher. Security-relevant events in this
 * service (login, logout, session-family revocation on token reuse,
 * registration, approval decisions) are dispatched to audit-service
 * through a Feign client carrying an audience-scoped service credential.
 *
 * <p>Delivery is lossy by design: dispatch runs on a bounded executor with
 * a discard policy, and every failure (audit-service unavailable, Feign
 * error, executor rejection) is logged and swallowed — audit emission must
 * never break or delay an authentication flow. The entry's actor is
 * attributed server-side by audit-service from the verified credential
 * subject ({@code auth-service}); callers of {@link #publish} can only
 * assert contextual user/clinic ids, never an actor.</p>
 *
 * <p>Caveat: emission is fire-and-forget and not transactionally coupled
 * to the operation it records, so an operation that rolls back after
 * emission may leave a stale event, and an operation that succeeds may
 * lose its event under audit-service outage. The audit trail is a
 * best-effort signal, not the system of record.</p>
 */
@Slf4j
@Component
public class AuditEventPublisher {

    private final AuditClient auditClient;
    private final Executor executor;

    public AuditEventPublisher(
            AuditClient auditClient,
            @Qualifier("auditEventExecutor") Executor executor) {
        this.auditClient = auditClient;
        this.executor = executor;
    }

    /**
     * Queues an audit event for asynchronous delivery. Never throws.
     *
     * @param action            event action, e.g. {@code LOGIN_SUCCESS}
     * @param target            event target, e.g. {@code user:42}
     * @param assertedUserId    caller-claimed user the event concerns (nullable)
     * @param assertedClinicId  caller-claimed clinic the event concerns (nullable)
     * @param context           additional event context (nullable)
     */
    public void publish(
            String action,
            String target,
            Long assertedUserId,
            Long assertedClinicId,
            Map<String, Object> context) {
        AuditEntryRequest request = AuditEntryRequest.builder()
                .action(action)
                .target(target)
                .assertedUserId(assertedUserId)
                .assertedClinicId(assertedClinicId)
                .context(context)
                .build();
        try {
            executor.execute(() -> send(request));
        } catch (RuntimeException ex) {
            log.warn("Audit event '{}' for {} was dropped before dispatch: {}",
                    action, target, ex.getMessage());
        }
    }

    private void send(AuditEntryRequest request) {
        try {
            auditClient.record(request);
        } catch (RuntimeException ex) {
            log.warn("Audit event '{}' for {} was not delivered: {}",
                    request.getAction(), request.getTarget(), ex.getMessage());
        }
    }
}
