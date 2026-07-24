package press.mizhifei.dentist.gateway.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import press.mizhifei.dentist.gateway.security.AccessTokenAuthenticationEntryPoint;
import press.mizhifei.dentist.gateway.security.RedisAccessTokenReactiveJwtDecoder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${jwt.issuer}")
    private String issuer;

    @Value("${jwt.audience:dentistdss-api}")
    private String audience;

    @Value("${springdoc.api-docs.enabled:false}")
    private boolean springdocEnabled;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            @Qualifier("accessTokenJwtDecoder") ReactiveJwtDecoder jwtDecoder,
            AccessTokenAuthenticationEntryPoint authenticationEntryPoint) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(exchanges -> {
                    exchanges.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    exchanges.pathMatchers(HttpMethod.GET,
                                    "/actuator/health",
                                    "/actuator/health/**",
                                    "/api/clinic/list/all",
                                    "/api/clinic/search",
                                    "/api/auth/csrf",
                                    "/api/auth/oauth2/jwks",
                                    "/oauth2/nonce")
                            .permitAll();
                    exchanges.pathMatchers(HttpMethod.POST,
                                    "/api/auth/login",
                                    "/api/auth/refresh",
                                    "/api/auth/logout",
                                    "/api/auth/signup",
                                    "/api/auth/signup/clinic/staff",
                                    "/api/auth/signup/clinic/admin",
                                    "/api/auth/signup/verify/code/resend",
                                    "/api/auth/signup/verify/code",
                                    "/oauth2/token")
                            .permitAll();
                    exchanges.matchers(GatewayRequestMatchers.ANONYMOUS_HELP)
                            .permitAll();
                    if (springdocEnabled) {
                        exchanges.pathMatchers(
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**",
                                        "/*/v3/api-docs",
                                        "/*/v3/api-docs/**")
                                .permitAll();
                    }
                    exchanges.anyExchange().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .authenticationFailureHandler(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder)));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(GlobalCorsProperties globalCorsProperties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        globalCorsProperties.getCorsConfigurations().forEach(source::registerCorsConfiguration);
        return source;
    }

    @Bean("localJwtDecoder")
    public ReactiveJwtDecoder localJwtDecoder() {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud",
                audiences -> audiences != null && audiences.contains(audience));
        OAuth2TokenValidator<Jwt> subjectValidator = new JwtClaimValidator<String>(
                "sub",
                this::isPositiveUserId);
        OAuth2TokenValidator<Jwt> tokenTypeValidator = new JwtClaimValidator<String>(
                "tokenType",
                "access"::equals);
        OAuth2TokenValidator<Jwt> tokenIdValidator = new JwtClaimValidator<String>(
                "jti",
                StringUtils::hasText);
        OAuth2TokenValidator<Jwt> securityVersionValidator = new JwtClaimValidator<Object>(
                "securityVersion",
                this::isPositiveIntegralValue);
        OAuth2TokenValidator<Jwt> sessionFamilyValidator = new JwtClaimValidator<Object>(
                "sessionFamilyId",
                value -> value instanceof String text && StringUtils.hasText(text));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator,
                subjectValidator,
                tokenTypeValidator,
                tokenIdValidator,
                securityVersionValidator,
                sessionFamilyValidator));
        return decoder;
    }

    @Bean("accessTokenJwtDecoder")
    @Primary
    public ReactiveJwtDecoder accessTokenJwtDecoder(
            @Qualifier("localJwtDecoder") ReactiveJwtDecoder localJwtDecoder,
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${app.security.access-token-state.timeout:PT2S}") Duration redisTimeout) {
        return new RedisAccessTokenReactiveJwtDecoder(
                localJwtDecoder,
                redisTemplate,
                redisTimeout);
    }

    private boolean isPositiveUserId(String subject) {
        if (!StringUtils.hasText(subject)) {
            return false;
        }
        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private boolean isPositiveIntegralValue(Object value) {
        if (value == null) {
            return false;
        }
        try {
            long parsed = switch (value) {
                case Byte number -> number.longValue();
                case Short number -> number.longValue();
                case Integer number -> number.longValue();
                case Long number -> number;
                case BigInteger number -> number.longValueExact();
                case BigDecimal number -> number.longValueExact();
                default -> 0L;
            };
            return parsed > 0;
        } catch (ArithmeticException error) {
            return false;
        }
    }
}
