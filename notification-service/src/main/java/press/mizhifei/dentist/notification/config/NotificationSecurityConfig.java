package press.mizhifei.dentist.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import press.mizhifei.dentist.security.ReactiveJwtResourceServerCustomizer;

@Configuration(proxyBeanMethods = false)
public class NotificationSecurityConfig {

    @Bean
    SecurityWebFilterChain notificationSecurityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtResourceServerCustomizer resourceServerCustomizer,
            @Value("${springdoc.api-docs.enabled:false}")
            boolean springdocEnabled) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(authorize -> {
                    authorize.pathMatchers(
                                    HttpMethod.GET,
                                    "/actuator/health",
                                    "/actuator/health/**")
                            .permitAll();
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
                .oauth2ResourceServer(resourceServerCustomizer);
        return http.build();
    }
}
