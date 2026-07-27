package press.mizhifei.dentist.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled audit-outbox relay
 * ({@link press.mizhifei.dentist.auth.audit.AuditOutboxService#relayPendingEvents()}).
 * Deliberately separate from {@link SchedulingConfig} (security-state
 * outbox): each worker is gated by its own property so disabling one can
 * never silently stall the other ({@code @EnableScheduling} is idempotent
 * when both are active).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.security.audit-outbox.enabled", havingValue = "true", matchIfMissing = true)
public class AuditOutboxSchedulingConfig {
}
