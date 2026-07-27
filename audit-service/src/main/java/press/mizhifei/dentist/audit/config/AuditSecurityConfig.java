package press.mizhifei.dentist.audit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import press.mizhifei.dentist.security.ReactiveBearerTokenFailureHandler;
import press.mizhifei.dentist.security.ReactiveJwtResourceServerCustomizer;
import press.mizhifei.dentist.security.servicetoken.ServiceAuthProperties;
import press.mizhifei.dentist.security.servicetoken.ServiceTokenReactiveJwtDecoder;
import press.mizhifei.dentist.security.servicetoken.ServiceTokenWebFilters;

/**
 * Audit-service security chain. Audit ingestion (POST /audit) is restricted to
 * verified service callers holding the {@code audit:ingest} scope — the actor
 * is the credential's cryptographic subject, never a caller-supplied field.
 * Audit reads (GET /audit) remain SYSTEM_ADMIN user-JWT only. Everything else
 * is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class AuditSecurityConfig {

    @Bean
    SecurityWebFilterChain auditSecurityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtResourceServerCustomizer resourceServerCustomizer,
            ServiceAuthProperties serviceAuthProperties,
            ReactiveBearerTokenFailureHandler failureHandler,
            @Value("${springdoc.api-docs.enabled:false}") boolean springdocEnabled) {
        // Stateless chain: ingestion takes verified service credentials and
        // reads take bearer user JWTs — credentials are attached explicitly,
        // never auto-attached by a browser, so no request requires CSRF
        // protection (CodeQL java/spring-disabled-csrf: match nothing rather
        // than disabling the filter).
        http.csrf(csrf -> csrf.requireCsrfProtectionMatcher(
                        exchange -> ServerWebExchangeMatcher.MatchResult.notMatch()))
                .cors(Customizer.withDefaults())
                .authorizeExchange(authorize -> {
                    authorize.pathMatchers(HttpMethod.GET,
                                    "/actuator/health", "/actuator/health/**")
                            .permitAll();
                    authorize.pathMatchers(HttpMethod.POST, "/audit")
                            .authenticated();
                    authorize.pathMatchers(HttpMethod.GET, "/audit/integrity")
                            .hasRole("SYSTEM_ADMIN");
                    authorize.pathMatchers(HttpMethod.GET, "/audit")
                            .hasRole("SYSTEM_ADMIN");
                    if (springdocEnabled) {
                        authorize.pathMatchers(
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**")
                                .authenticated();
                    }
                    authorize.anyExchange().denyAll();
                })
                .addFilterAt(
                        serviceAuthenticationFilter(serviceAuthProperties, failureHandler),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .oauth2ResourceServer(resourceServerCustomizer);
        return http.build();
    }

    private AuthenticationWebFilter serviceAuthenticationFilter(
            ServiceAuthProperties serviceAuthProperties,
            ReactiveBearerTokenFailureHandler failureHandler) {
        ServiceTokenReactiveJwtDecoder decoder = new ServiceTokenReactiveJwtDecoder(
                serviceAuthProperties.trustedServiceKeys(),
                serviceAuthProperties.getAudience());
        return ServiceTokenWebFilters.reactiveAuthenticationFilter(
                decoder,
                ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/audit"),
                failureHandler);
    }
}
