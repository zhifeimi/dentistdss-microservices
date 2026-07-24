package press.mizhifei.dentist.userprofile.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import press.mizhifei.dentist.security.ServletBearerTokenFailureHandler;
import press.mizhifei.dentist.security.ServletJwtResourceServerCustomizer;
import press.mizhifei.dentist.security.servicetoken.ServiceAuthProperties;
import press.mizhifei.dentist.security.servicetoken.ServiceTokenAuthenticationFilter;
import press.mizhifei.dentist.security.servicetoken.ServiceTokenJwtDecoder;

/**
 * User profile service security boundary. Every route requires a verified
 * user JWT; role and ownership rules are enforced by the controllers from the
 * JWT claims. Forwarded identity headers are not trust evidence.
 *
 * <p>Contact-read routes ({@code GET /user/{id}/email} and
 * {@code GET /user/{id}/name}) additionally accept audience-scoped service
 * credentials holding the {@code user:contact:read} scope (granted to
 * notification-service) so it can resolve recipient addresses without a user
 * session. Credentials failing validation fail closed: unknown/forged
 * credentials render as 401; an empty trust map renders a sanitized 503.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class UserProfileSecurityConfig {

    @Bean
    SecurityFilterChain userProfileSecurityFilterChain(
            HttpSecurity http,
            ServletJwtResourceServerCustomizer resourceServerCustomizer,
            ServiceAuthProperties serviceAuthProperties,
            ServletBearerTokenFailureHandler bearerTokenFailureHandler,
            @Value("${springdoc.api-docs.enabled:false}")
            boolean springdocEnabled) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/user/**", "/patient/**", "/dentist/**"))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(
                                    HttpMethod.GET,
                                    "/actuator/health",
                                    "/actuator/health/**")
                            .permitAll();
                    authorize.requestMatchers("/user/**", "/patient/**", "/dentist/**")
                            .authenticated();
                    if (springdocEnabled) {
                        authorize.requestMatchers(
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**")
                                .authenticated();
                    }
                    authorize.anyRequest().denyAll();
                })
                .addFilterBefore(
                        serviceAuthenticationFilter(
                                serviceAuthProperties, bearerTokenFailureHandler),
                        BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(resourceServerCustomizer);
        return http.build();
    }

    private static ServiceTokenAuthenticationFilter serviceAuthenticationFilter(
            ServiceAuthProperties serviceAuthProperties,
            ServletBearerTokenFailureHandler failureHandler) {
        ServiceTokenJwtDecoder decoder = new ServiceTokenJwtDecoder(
                serviceAuthProperties.trustedServiceKeys(),
                serviceAuthProperties.getAudience());
        RequestMatcher contactReads = new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/user/*/email"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/user/*/name"));
        return new ServiceTokenAuthenticationFilter(decoder, contactReads, failureHandler);
    }
}
