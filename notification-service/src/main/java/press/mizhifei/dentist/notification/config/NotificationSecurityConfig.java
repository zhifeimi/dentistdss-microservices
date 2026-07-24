package press.mizhifei.dentist.notification.config;

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
 * Notification-service security chain. Two machine-to-machine surfaces accept
 * verified service credentials only: in-app notification creation
 * ({@code POST /notification/send}, scope {@code notification:send} —
 * appointment and clinical-records callers) and transactional email
 * ({@code POST /notification/email/**}, scope {@code notification:email} —
 * auth-service caller). The per-scope authorities keep one caller's key from
 * reaching the other surface. User-facing reads stay user-JWT authenticated;
 * everything else is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class NotificationSecurityConfig {

    @Bean
    SecurityWebFilterChain notificationSecurityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtResourceServerCustomizer resourceServerCustomizer,
            ServiceAuthProperties serviceAuthProperties,
            ReactiveBearerTokenFailureHandler failureHandler,
            @Value("${springdoc.api-docs.enabled:false}")
            boolean springdocEnabled) {
        // Stateless chain: machine surfaces take verified service credentials
        // and user surfaces take bearer user JWTs — credentials are attached
        // explicitly, never auto-attached by a browser, so no request requires
        // CSRF protection (CodeQL java/spring-disabled-csrf: match nothing
        // rather than disabling the filter).
        http.csrf(csrf -> csrf.requireCsrfProtectionMatcher(
                        exchange -> ServerWebExchangeMatcher.MatchResult.notMatch()))
                .cors(Customizer.withDefaults())
                .authorizeExchange(authorize -> {
                    authorize.pathMatchers(
                                    HttpMethod.GET,
                                    "/actuator/health",
                                    "/actuator/health/**")
                            .permitAll();
                    authorize.pathMatchers(HttpMethod.POST, "/notification/send")
                            .hasAuthority("SERVICE_NOTIFICATION_SEND");
                    authorize.pathMatchers(HttpMethod.POST, "/notification/email/**")
                            .hasAuthority("SERVICE_NOTIFICATION_EMAIL");
                    authorize.pathMatchers(
                                    HttpMethod.GET,
                                    "/notification/user/{userId}",
                                    "/notification/user/{userId}/unread-count")
                            .authenticated();
                    authorize.pathMatchers(
                                    HttpMethod.PATCH,
                                    "/notification/{id}/read")
                            .authenticated();
                    authorize.pathMatchers(
                                    HttpMethod.PUT,
                                    "/notification/{id}/read")
                            .authenticated();
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
                ServerWebExchangeMatchers.pathMatchers(
                        HttpMethod.POST,
                        "/notification/send",
                        "/notification/email/**"),
                failureHandler);
    }
}
