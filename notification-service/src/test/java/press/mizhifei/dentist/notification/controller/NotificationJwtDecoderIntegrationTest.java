package press.mizhifei.dentist.notification.controller;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import press.mizhifei.dentist.notification.config.NotificationSecurityConfig;
import press.mizhifei.dentist.notification.service.NotificationService;
import press.mizhifei.dentist.security.ReactiveJwtSecurityAutoConfiguration;
import press.mizhifei.dentist.security.RedisAccessTokenReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@WebFluxTest(NotificationController.class)
@ImportAutoConfiguration(ReactiveWebSecurityAutoConfiguration.class)
@Import({
        NotificationSecurityConfig.class,
        ReactiveJwtSecurityAutoConfiguration.class
})
@TestPropertySource(properties = {
        "jwt.issuer=https://issuer.example",
        "jwt.audience=dentistdss-api",
        "springdoc.api-docs.enabled=false",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class NotificationJwtDecoderIntegrationTest {

    private static final String ISSUER = "https://issuer.example";
    private static final String AUDIENCE = "dentistdss-api";
    private static final RSAKey SIGNING_KEY = createKey("trusted-key");
    private static final HttpServer JWK_SERVER = startJwkServer();

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @MockitoBean
    private ReactiveValueOperations<String, String> redisValueOperations;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/jwks");
    }

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
        when(notificationService.getUserNotifications(42L, 42L)).thenReturn(List.of());
        when(redisTemplate.opsForValue()).thenReturn(redisValueOperations);
        setRedisState("1:1", "active");
    }

    @AfterAll
    static void stopJwkServer() {
        JWK_SERVER.stop(0);
    }

    @Test
    void usesStrictSharedReactiveJwtDecoderInsteadOfBootDefault() {
        assertTrue(applicationContext.containsBean("dentistDssAccessTokenReactiveJwtDecoder"));
        assertTrue(applicationContext.getBean("dentistDssAccessTokenReactiveJwtDecoder")
                instanceof RedisAccessTokenReactiveJwtDecoder);
        assertFalse(applicationContext.containsBean("reactiveJwtDecoder"));
        assertFalse(applicationContext.containsBean("jwtDecoder"));
    }

    @Test
    void acceptsValidSignedTokenWithCurrentAccountAndActiveFamily() throws Exception {
        getNotifications(signedToken(1L, "family-1"))
                .expectStatus().isOk();
    }

    @Test
    void rejectsInactiveAccountAsInvalidToken() throws Exception {
        setRedisState("1:0", "active");

        expectInvalidToken(signedToken(1L, "family-1"));
    }

    @Test
    void rejectsSecurityVersionMismatchAsInvalidToken() throws Exception {
        setRedisState("2:1", "active");

        expectInvalidToken(signedToken(1L, "family-1"));
    }

    @Test
    void rejectsRevokedFamilyAsInvalidToken() throws Exception {
        setRedisState("1:1", "revoked");

        expectInvalidToken(signedToken(1L, "family-1"));
    }

    @Test
    void returnsSanitizedServiceUnavailableForMalformedFamilyState() throws Exception {
        setRedisState("1:1", "ACTIVE");

        expectStateUnavailable(signedToken(1L, "family-1"));
    }

    @Test
    void returnsSanitizedServiceUnavailableForMissingState() throws Exception {
        when(redisValueOperations.multiGet(stateKeys())).thenReturn(Mono.empty());

        expectStateUnavailable(signedToken(1L, "family-1"));
    }

    @Test
    void returnsSanitizedServiceUnavailableForRedisFailure() throws Exception {
        when(redisValueOperations.multiGet(stateKeys()))
                .thenReturn(Mono.error(new IllegalStateException("redis details")));

        expectStateUnavailable(signedToken(1L, "family-1"));
    }

    private void setRedisState(String accountState, String familyState) {
        when(redisValueOperations.multiGet(stateKeys()))
                .thenReturn(Mono.just(List.of(accountState, familyState)));
    }

    private List<String> stateKeys() {
        return List.of(
                "security:access:v1:{42}:account",
                "security:access:v1:{42}:family:family-1");
    }

    private void expectInvalidToken(String token) {
        getNotifications(token)
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, ".*invalid_token.*");
    }

    private void expectStateUnavailable(String token) {
        getNotifications(token)
                .expectStatus().isEqualTo(503)
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, ".*no-store.*")
                .expectBody().isEmpty();
    }

    private WebTestClient.ResponseSpec getNotifications(String token) {
        return webTestClient.get()
                .uri("/notification/user/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private static String signedToken(long securityVersion, String familyId) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("42")
                .jwtID("token-1")
                .issueTime(Date.from(now.minusSeconds(1)))
                .notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tokenType", "access")
                .claim("securityVersion", securityVersion)
                .claim("sessionFamilyId", familyId)
                .claim("roles", List.of("PATIENT"))
                .claim("clinicId", 9L)
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(SIGNING_KEY.getKeyID())
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey createKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(keyId)
                    .generate();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static HttpServer startJwkServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwkSet = new JWKSet(SIGNING_KEY.toPublicJWK())
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set(
                        HttpHeaders.CONTENT_TYPE,
                        "application/json");
                exchange.sendResponseHeaders(200, jwkSet.length);
                exchange.getResponseBody().write(jwkSet);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
