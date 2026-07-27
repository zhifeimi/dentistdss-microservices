package press.mizhifei.dentist.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import reactor.core.publisher.Mono;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccessTokenBearerFilterTest {

    private static final String ISSUER = "https://issuer.example";
    private static final String AUDIENCE = "dentistdss-api";

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    @BeforeAll
    static void generateSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        publicKey = (RSAPublicKey) keyPair.getPublic();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void servletBearerFilterKeepsInvalidStateAsRfc6750Unauthorized() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(List.of("3:0", "active"));
        BearerTokenAuthenticationFilter filter = servletFilter(redisTemplate);
        MockHttpServletResponse response = invokeServlet(filter, signedToken());

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        assertTrue(response.getHeader(HttpHeaders.WWW_AUTHENTICATE).contains("error=\"invalid_token\""));
    }

    @Test
    void servletBearerFilterSanitizesRedisFailureAsServiceUnavailable() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenThrow(
                new RedisConnectionFailureException("redis-secret.internal:6379"));
        BearerTokenAuthenticationFilter filter = servletFilter(redisTemplate);
        MockHttpServletResponse response = invokeServlet(filter, signedToken());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getStatus());
        assertNull(response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("", response.getContentAsString());
        assertFalse(response.getContentAsString().contains("redis-secret"));
    }

    @Test
    void servletBearerFilterRejectsTamperedSignatureBeforeRedis() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        BearerTokenAuthenticationFilter filter = servletFilter(redisTemplate);
        MockHttpServletResponse response = invokeServlet(filter, signedToken() + "x");

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void reactiveBearerFilterKeepsRevokedFamilyAsRfc6750Unauthorized() throws Exception {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(Mono.just(List.of("3:1", "revoked")));
        AuthenticationWebFilter filter = reactiveFilter(redisTemplate);
        MockServerWebExchange exchange = invokeReactive(filter, signedToken());

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)
                .contains("error=\"invalid_token\""));
    }

    @Test
    void reactiveBearerFilterSanitizesRedisFailureAsServiceUnavailable() throws Exception {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(Mono.error(
                new RedisConnectionFailureException("redis-secret.internal:6379")));
        AuthenticationWebFilter filter = reactiveFilter(redisTemplate);
        MockServerWebExchange exchange = invokeReactive(filter, signedToken());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertNull(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("", exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(1)));
    }

    @Test
    void reactiveBearerFilterUsesNonBlockingDecoderAndCallsChainForCurrentToken() throws Exception {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(Mono.just(List.of("3:1", "active")));
        AuthenticationWebFilter filter = reactiveFilter(redisTemplate);
        AtomicBoolean chainCalled = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/secure")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signedToken()));

        filter.filter(exchange, ignored -> {
                    chainCalled.set(true);
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(2));

        assertTrue(chainCalled.get());
    }

    private BearerTokenAuthenticationFilter servletFilter(StringRedisTemplate redisTemplate) {
        NimbusJwtDecoder delegate = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        delegate.setJwtValidator(DentistDssJwtValidators.create(ISSUER, AUDIENCE));
        RedisAccessTokenJwtDecoder decoder = new RedisAccessTokenJwtDecoder(delegate, redisTemplate);
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(JwtAuthorityConverters.rolesConverter());
        BearerTokenAuthenticationFilter filter = new BearerTokenAuthenticationFilter(
                new ProviderManager(provider));
        ServletBearerTokenFailureHandler failureHandler = new ServletBearerTokenFailureHandler();
        filter.setAuthenticationEntryPoint(failureHandler);
        filter.setAuthenticationFailureHandler(failureHandler);
        return filter;
    }

    private AuthenticationWebFilter reactiveFilter(ReactiveStringRedisTemplate redisTemplate) {
        NimbusReactiveJwtDecoder delegate = NimbusReactiveJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        delegate.setJwtValidator(DentistDssJwtValidators.create(ISSUER, AUDIENCE));
        RedisAccessTokenReactiveJwtDecoder decoder = new RedisAccessTokenReactiveJwtDecoder(
                delegate,
                redisTemplate,
                Duration.ofSeconds(1));
        JwtReactiveAuthenticationManager authenticationManager = new JwtReactiveAuthenticationManager(decoder);
        AuthenticationWebFilter filter = new AuthenticationWebFilter(authenticationManager);
        filter.setServerAuthenticationConverter(new ServerBearerTokenAuthenticationConverter());
        filter.setAuthenticationFailureHandler(new ReactiveBearerTokenFailureHandler());
        return filter;
    }

    private MockHttpServletResponse invokeServlet(
            BearerTokenAuthenticationFilter filter,
            String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockServerWebExchange invokeReactive(
            AuthenticationWebFilter filter,
            String token) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/secure")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        filter.filter(exchange, ignored -> Mono.empty()).block(Duration.ofSeconds(2));
        return exchange;
    }

    private String signedToken() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("42")
                .jwtID("token-id")
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tokenType", "access")
                .claim("securityVersion", 3L)
                .claim("sessionFamilyId", "family-1")
                .claim("roles", List.of("SYSTEM_ADMIN"))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }
}
