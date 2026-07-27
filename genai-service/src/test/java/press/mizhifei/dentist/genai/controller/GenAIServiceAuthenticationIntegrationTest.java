package press.mizhifei.dentist.genai.controller;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
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
import reactor.core.publisher.Mono;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@WebFluxTest(GenAIController.class)
@ImportAutoConfiguration(ReactiveWebSecurityAutoConfiguration.class)
@Import({
        GenAISecurityConfig.class,
        ReactiveJwtSecurityAutoConfiguration.class,
        GenAIPromptValidator.class,
        UserContextService.class,
        GenAIServiceJwtDecoder.class
})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.example/jwks",
        "jwt.issuer=https://issuer.example",
        "jwt.audience=dentistdss-api",
        "springdoc.api-docs.enabled=false",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class GenAIServiceAuthenticationIntegrationTest {

    private static final String KEY_ID = "gateway-genai-2026-01";
    private static final String SESSION_ID = "S".repeat(43);
    private static final String PROOF = "P".repeat(43);
    private static final String SOURCE_FINGERPRINT = "a".repeat(64);
    private static final RSAKey SIGNING_KEY = createKey();

    @Autowired
    private ApplicationContext applicationContext;

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

    @MockitoBean(name = "dentistDssAccessTokenReactiveJwtDecoder")
    private ReactiveJwtDecoder userJwtDecoder;

    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void serviceJwtProperties(DynamicPropertyRegistry registry) {
        registry.add("app.security.genai-service-auth.public-key",
                () -> publicKeyPem(SIGNING_KEY));
        registry.add("app.security.genai-service-auth.key-id", () -> KEY_ID);
    }

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
        when(anonymousSessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF))
                .thenReturn(Mono.just(new AnonymousSessionRegistry.VerifiedAnonymousSession(
                        SESSION_ID,
                        SOURCE_FINGERPRINT)));
        when(tokenRateLimiter.tryConsume(anyString(), anyLong()))
                .thenReturn(Mono.just(false));
    }

    @Test
    void validServiceCredentialAndOneTimeProofReachAnonymousHelp() throws Exception {
        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME,
                        "Bearer " + signedServiceToken())
                .bodyValue("help")
                .exchange()
                .expectStatus().isOk();

        verify(anonymousSessionRegistry).requireGatewayIssuedSession(SESSION_ID, PROOF);
        verify(tokenRateLimiter).tryConsume("source:" + SOURCE_FINGERPRINT, 1L);
    }

    @Test
    void serviceCredentialIsIgnoredOutsideExactPostHelpRoute() throws Exception {
        webTestClient.post()
                .uri("/genai/chatbot/triage")
                .contentType(MediaType.TEXT_PLAIN)
                .header(GenAIServiceJwtDecoder.HEADER_NAME,
                        "Bearer " + signedServiceToken())
                .bodyValue("symptoms")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/genai/chatbot/help")
                .header(GenAIServiceJwtDecoder.HEADER_NAME,
                        "Bearer " + signedServiceToken())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static String signedServiceToken() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://api-gateway.dentistdss.internal")
                .subject("api-gateway")
                .audience("genai-service")
                .jwtID(java.util.UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(30)))
                .claim("tokenType", "service")
                .claim("scope", "genai:anonymous-help")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(KEY_ID)
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey createKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static String publicKeyPem(RSAKey key) {
        try {
            byte[] encoded = ((RSAPublicKey) key.toRSAPublicKey()).getEncoded();
            return "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'})
                    .encodeToString(encoded)
                    + "\n-----END PUBLIC KEY-----";
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
