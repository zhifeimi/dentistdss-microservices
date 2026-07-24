package press.mizhifei.dentist.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

import java.time.Duration;

@AutoConfiguration(before = ReactiveOAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableReactiveMethodSecurity
public class ReactiveJwtSecurityAutoConfiguration {

    @Bean
    @Primary
    RedisAccessTokenReactiveJwtDecoder dentistDssAccessTokenReactiveJwtDecoder(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.audience:dentistdss-api}") String audience,
            @Value("${security.access-token-state.redis-timeout:500ms}") Duration redisTimeout) {
        NimbusReactiveJwtDecoder delegate = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        delegate.setJwtValidator(DentistDssJwtValidators.create(issuer, audience));
        return new RedisAccessTokenReactiveJwtDecoder(delegate, redisTemplate, redisTimeout);
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveJwtAuthenticationConverterAdapter reactiveJwtAuthenticationConverter() {
        return new ReactiveJwtAuthenticationConverterAdapter(JwtAuthorityConverters.rolesConverter());
    }

    @Bean
    ReactiveBearerTokenFailureHandler dentistDssReactiveBearerTokenFailureHandler() {
        return new ReactiveBearerTokenFailureHandler();
    }

    @Bean
    ReactiveJwtResourceServerCustomizer dentistDssReactiveJwtResourceServerCustomizer(
            @Qualifier("dentistDssAccessTokenReactiveJwtDecoder") ReactiveJwtDecoder jwtDecoder,
            ReactiveJwtAuthenticationConverterAdapter authenticationConverter,
            ReactiveBearerTokenFailureHandler failureHandler) {
        return new ReactiveJwtResourceServerCustomizer(
                jwtDecoder,
                authenticationConverter,
                failureHandler);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityWebFilterChain.class)
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtResourceServerCustomizer resourceServerCustomizer,
            @Value("${springdoc.api-docs.enabled:false}") boolean springdocEnabled) {
        // Stateless bearer-token fallback chain: credentials are attached
        // explicitly, never auto-attached by a browser, so no request requires
        // CSRF protection (CodeQL java/spring-disabled-csrf: match nothing
        // rather than disabling the filter).
        http.csrf(csrf -> csrf.requireCsrfProtectionMatcher(
                        exchange -> ServerWebExchangeMatcher.MatchResult.notMatch()))
                .cors(Customizer.withDefaults())
                .authorizeExchange(authorize -> {
                    authorize.pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();
                    if (springdocEnabled) {
                        authorize.pathMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    authorize.anyExchange().authenticated();
                })
                .oauth2ResourceServer(resourceServerCustomizer);
        return http.build();
    }
}
