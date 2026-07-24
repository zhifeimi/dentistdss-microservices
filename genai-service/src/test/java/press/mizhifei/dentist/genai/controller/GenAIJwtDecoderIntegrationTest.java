package press.mizhifei.dentist.genai.controller;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import press.mizhifei.dentist.genai.config.GenAISecurityConfig;
import press.mizhifei.dentist.genai.security.GenAIServiceJwtDecoder;
import press.mizhifei.dentist.genai.service.AnonymousSessionRegistry;
import press.mizhifei.dentist.genai.service.ChatService;
import press.mizhifei.dentist.genai.service.ConversationPersistenceService;
import press.mizhifei.dentist.genai.service.GenAIInteractionService;
import press.mizhifei.dentist.genai.service.GenAIPromptValidator;
import press.mizhifei.dentist.genai.service.TokenRateLimiter;
import press.mizhifei.dentist.genai.service.UserContextService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@WebFluxTest(GenAIController.class)
@ImportAutoConfiguration(ReactiveWebSecurityAutoConfiguration.class)
@Import({
        GenAISecurityConfig.class,
        GenAIServiceJwtDecoder.class,
        ReactiveJwtSecurityAutoConfiguration.class,
        GenAIPromptValidator.class,
        UserContextService.class
})
@TestPropertySource(properties = {
        "jwt.issuer=https://issuer.example",
        "jwt.audience=dentistdss-api",
        "springdoc.api-docs.enabled=false",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class GenAIJwtDecoderIntegrationTest {

    private static final String ISSUER = "https://issuer.example";
    private static final String AUDIENCE = "dentistdss-api";
    private static final RSAKey SIGNING_KEY = createKey("trusted-key");
    private static final RSAKey UNTRUSTED_KEY = createKey("untrusted-key");
    private static final HttpServer JWK_SERVER = startJwkServer();

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private TokenRateLimiter tokenRateLimiter;

    @MockitoBean
    private ConversationPersistenceService conversationPersistenceService;

    @MockitoBean
    private GenAIInteractionService interactionService;

    @MockitoBean
    private AnonymousSessionRegistry anonymousSessionRegistry;

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
        when(tokenRateLimiter.tryConsume(anyString(), anyLong())).thenReturn(Mono.just(false));
        when(redisTemplate.opsForValue()).thenReturn(redisValueOperations);
        when(redisValueOperations.multiGet(List.of(
                "security:access:v1:{42}:account",
                "security:access:v1:{42}:family:family-1")))
                .thenReturn(Mono.just(List.of("1:1", "active")));
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
    void returnsSanitizedServiceUnavailableWhenServiceAuthIsNotConfigured() {
        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Gateway-Service-Authorization", "Bearer service-token")
                .bodyValue("help")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, ".*no-store.*")
                .expectBody().isEmpty();
    }

    @Test
    void acceptsValidSignedAccessTokenAndMapsRolesClaim() throws Exception {
        postTriage(signedToken(ISSUER, AUDIENCE, "access", "token-1", "42", SIGNING_KEY))
                .expectStatus().isOk();
    }

    @Test
    void rejectsRevokedFamilyAsInvalidToken() throws Exception {
        when(redisValueOperations.multiGet(List.of(
                "security:access:v1:{42}:account",
                "security:access:v1:{42}:family:family-1")))
                .thenReturn(Mono.just(List.of("1:1", "revoked")));

        postTriage(signedToken(ISSUER, AUDIENCE, "access", "revoked", "42", SIGNING_KEY))
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, ".*invalid_token.*");
    }

    @Test
    void returnsSanitizedServiceUnavailableForMalformedFamilyState() throws Exception {
        when(redisValueOperations.multiGet(List.of(
                "security:access:v1:{42}:account",
                "security:access:v1:{42}:family:family-1")))
                .thenReturn(Mono.just(List.of("1:1", "ACTIVE")));

        postTriage(signedToken(ISSUER, AUDIENCE, "access", "malformed-state", "42", SIGNING_KEY))
                .expectStatus().isEqualTo(503)
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, ".*no-store.*")
                .expectBody().isEmpty();
    }

    @Test
    void rejectsTokenWithWrongIssuer() throws Exception {
        postTriage(signedToken(
                "https://wrong-issuer.example",
                AUDIENCE,
                "access",
                "token-2",
                "42",
                SIGNING_KEY))
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenWithWrongAudience() throws Exception {
        postTriage(signedToken(
                ISSUER,
                "wrong-audience",
                "access",
                "token-3",
                "42",
                SIGNING_KEY))
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsRefreshToken() throws Exception {
        postTriage(signedToken(
                ISSUER,
                AUDIENCE,
                "refresh",
                "token-4",
                "42",
                SIGNING_KEY))
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenWithoutJti() throws Exception {
        postTriage(signedToken(
                ISSUER,
                AUDIENCE,
                "access",
                null,
                "42",
                SIGNING_KEY))
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenWithoutSubject() throws Exception {
        postTriage(signedToken(
                ISSUER,
                AUDIENCE,
                "access",
                "token-5",
                null,
                SIGNING_KEY))
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenWithNonNumericSubject() throws Exception {
        postTriage(signedToken(
                ISSUER,
                AUDIENCE,
                "access",
                "token-6",
                "user-42",
                SIGNING_KEY))
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenSignedByUnknownKey() throws Exception {
        postTriage(signedToken(
                ISSUER,
                AUDIENCE,
                "access",
                "token-7",
                "42",
                UNTRUSTED_KEY))
                .expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec postTriage(String token) {
        return webTestClient.post()
                .uri("/genai/chatbot/triage")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", "abcdefghijklmnopqrstuvwxyzABCDEFGH012345678")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue("symptoms")
                .exchange();
    }

    private static String signedToken(
            String issuer,
            String audience,
            String tokenType,
            String jti,
            String subject,
            RSAKey signingKey) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(1)))
                .notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tokenType", tokenType)
                .claim("securityVersion", 1L)
                .claim("sessionFamilyId", "family-1")
                .claim("roles", List.of("PATIENT"))
                .claim("clinicId", 9L);
        if (jti != null) {
            claims.jwtID(jti);
        }
        if (subject != null) {
            claims.subject(subject);
        }

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(signingKey.getKeyID())
                        .build(),
                claims.build());
        jwt.sign(new RSASSASigner(signingKey));
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
                        MediaType.APPLICATION_JSON_VALUE);
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
