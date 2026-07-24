package press.mizhifei.dentist.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import press.mizhifei.dentist.security.servicetoken.ServiceAuthProperties;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Wires the best-effort audit emission path: the bounded executor backing
 * {@link press.mizhifei.dentist.auth.audit.AuditEventPublisher}, and the
 * {@link ServiceAuthProperties} used by this service's per-client Feign
 * service-token interceptors.
 *
 * <p>The executor is deliberately small (1 core / 2 max / queue 500) with a
 * discard policy: audit traffic must never contend with authentication
 * traffic for threads, and overload drops events rather than blocking
 * request threads or growing memory without bound.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class AuditEmitterConfig {

    @Bean("auditEventExecutor")
    ThreadPoolTaskExecutor auditEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        return executor;
    }
}
