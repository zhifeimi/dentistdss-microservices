package press.mizhifei.dentist.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import press.mizhifei.dentist.security.servicetoken.ServiceAuthProperties;

/**
 * Enables the {@link ServiceAuthProperties} consumed by this service's
 * per-client Feign service-token interceptors (audit ingestion and
 * notification email). Audit emission itself is durable: events are written
 * to the transactional outbox and delivered by the scheduled relay
 * ({@link press.mizhifei.dentist.auth.audit.AuditOutboxService}), so the
 * former lossy executor bean is gone.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class AuditClientConfig {
}
