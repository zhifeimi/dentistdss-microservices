package press.mizhifei.dentist.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled security-state outbox workers. Scheduling can be
 * switched off entirely (for example in short-lived command-line runs) with
 * {@code app.security.outbox.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.security.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
