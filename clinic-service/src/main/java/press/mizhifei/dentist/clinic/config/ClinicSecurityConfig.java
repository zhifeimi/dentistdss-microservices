package press.mizhifei.dentist.clinic.config;

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
 * Clinic service security boundary. Only the clinic list and search used by
 * anonymous booking and the read-only public clinic directory (SEO prerender) stay
 * open — every other route requires a verified user JWT. Role and
 * clinic-ownership rules are enforced by the controllers from the JWT
 * claims; forwarded identity headers are not trust evidence.
 */
@Configuration(proxyBeanMethods = false)
public class ClinicSecurityConfig {

    @Bean
    SecurityFilterChain clinicSecurityFilterChain(
            HttpSecurity http,
            ServletJwtResourceServerCustomizer resourceServerCustomizer,
            @Value("${springdoc.api-docs.enabled:false}")
            boolean springdocEnabled) {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/clinic/**"))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(
                                    HttpMethod.GET,
                                    "/actuator/health",
                                    "/actuator/health/**",
                                    "/clinic/list/all",
                                    // Public clinic directory (SEO prerender);
                                    // only approved, opted-in clinics.
                                    "/public/clinics",
                                    "/public/clinics/*")
                            .permitAll();
                    authorize.requestMatchers(HttpMethod.POST, "/clinic/search")
                            .permitAll();
                    authorize.requestMatchers("/clinic/**")
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
