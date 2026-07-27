package press.mizhifei.dentist.auth.client;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import press.mizhifei.dentist.security.servicetoken.ServiceAuthProperties;
import press.mizhifei.dentist.security.servicetoken.ServiceTokenRequestInterceptor;

/**
 * Per-client Feign configuration for {@link AuditClient}. Deliberately NOT
 * annotated {@code @Configuration}: it is applied only to this client
 * through {@code @FeignClient(configuration = ...)}, so its interceptor
 * never applies to other Feign clients in this service.
 *
 * <p>Attaches a fresh 30-second credential with audience
 * {@code audit-service} and scope {@code audit:ingest} to each call. With
 * issuer keys unconfigured (local dev) the interceptor stays dormant and
 * the call proceeds without the header; audit-service then rejects the
 * unauthenticated call, and the best-effort publisher logs and drops the
 * event.</p>
 */
public class AuditFeignConfiguration {

    @Bean
    RequestInterceptor auditServiceTokenInterceptor(
            ServiceAuthProperties serviceAuthProperties) {
        return new ServiceTokenRequestInterceptor(
                serviceAuthProperties.issuer(),
                "audit-service",
                "audit:ingest");
    }
}
