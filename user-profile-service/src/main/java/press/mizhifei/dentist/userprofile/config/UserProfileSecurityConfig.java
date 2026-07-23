package press.mizhifei.dentist.userprofile.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import press.mizhifei.dentist.security.ServletJwtResourceServerCustomizer;

/**
 * User profile service security boundary. Every route requires a verified
 * user JWT; role and ownership rules are enforced by the controllers from the
 * JWT claims. Forwarded identity headers are not trust evidence.
 */
@Configuration(proxyBeanMethods = false)
public class UserProfileSecurityConfig {

    @Bean
    SecurityFilterChain userProfileSecurityFilterChain(
            HttpSecurity http,
            ServletJwtResourceServerCustomizer resourceServerCustomizer,
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
                .oauth2ResourceServer(resourceServerCustomizer);
        return http.build();
    }
}
