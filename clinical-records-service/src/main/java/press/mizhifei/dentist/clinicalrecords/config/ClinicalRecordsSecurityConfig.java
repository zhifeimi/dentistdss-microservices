package press.mizhifei.dentist.clinicalrecords.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import press.mizhifei.dentist.security.ServletJwtResourceServerCustomizer;
import press.mizhifei.dentist.security.servicetoken.ServiceAuthProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class ClinicalRecordsSecurityConfig {

    @Bean
    SecurityFilterChain clinicalRecordsSecurityFilterChain(
            HttpSecurity http,
            ServletJwtResourceServerCustomizer resourceServerCustomizer,
            @Value("${springdoc.api-docs.enabled:false}") boolean springdocEnabled) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/clinical-records/**"))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(
                                    HttpMethod.GET,
                                    "/actuator/health",
                                    "/actuator/health/**")
                            .permitAll();
                    authorize.requestMatchers("/clinical-records/**")
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
