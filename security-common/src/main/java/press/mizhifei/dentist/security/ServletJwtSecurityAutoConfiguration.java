package press.mizhifei.dentist.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@AutoConfiguration(before = OAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class ServletJwtSecurityAutoConfiguration {

    @Bean
    @Primary
    RedisAccessTokenJwtDecoder dentistDssAccessTokenJwtDecoder(
            StringRedisTemplate redisTemplate,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.audience:dentistdss-api}") String audience) {
        NimbusJwtDecoder delegate = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        delegate.setJwtValidator(DentistDssJwtValidators.create(issuer, audience));
        return new RedisAccessTokenJwtDecoder(delegate, redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        return JwtAuthorityConverters.rolesConverter();
    }

    @Bean
    ServletBearerTokenFailureHandler dentistDssServletBearerTokenFailureHandler() {
        return new ServletBearerTokenFailureHandler();
    }

    @Bean
    ServletJwtResourceServerCustomizer dentistDssServletJwtResourceServerCustomizer(
            RedisAccessTokenJwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ServletBearerTokenFailureHandler failureHandler) {
        return new ServletJwtResourceServerCustomizer(
                jwtDecoder,
                jwtAuthenticationConverter,
                failureHandler);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ServletJwtResourceServerCustomizer resourceServerCustomizer,
            @Value("${springdoc.api-docs.enabled:false}") boolean springdocEnabled) throws Exception {
        // Stateless bearer-token fallback chain: credentials are attached
        // explicitly, never auto-attached by a browser, so no request requires
        // CSRF protection (CodeQL java/spring-disabled-csrf: match nothing
        // rather than disabling the filter).
        http.csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> false))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();
                    if (springdocEnabled) {
                        authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .oauth2ResourceServer(resourceServerCustomizer);
        return http.build();
    }
}
