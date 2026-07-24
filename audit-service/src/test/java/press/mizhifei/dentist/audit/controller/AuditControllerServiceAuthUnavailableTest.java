package press.mizhifei.dentist.audit.controller;

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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import press.mizhifei.dentist.audit.config.AuditSecurityConfig;
import press.mizhifei.dentist.audit.service.AuditIntegrityService;
import press.mizhifei.dentist.audit.service.AuditService;
import press.mizhifei.dentist.security.ReactiveJwtSecurityAutoConfiguration;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Fail-closed behavior when no service credentials are configured (the trust
 * map is empty because the key material environment variables are unset):
 * ingestion attempts with a service credential are rejected with a sanitized
 * 503 — never accepted, never leaking decoder internals.
 */
@WebFluxTest(AuditController.class)
@ImportAutoConfiguration(ReactiveWebSecurityAutoConfiguration.class)
@Import({AuditSecurityConfig.class, ReactiveJwtSecurityAutoConfiguration.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.example/jwks",
        "jwt.issuer=https://issuer.example",
        "jwt.audience=dentistdss-api",
        "springdoc.api-docs.enabled=false",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class AuditControllerServiceAuthUnavailableTest {

    private static final RSAKey SIGNING_KEY = createKey();

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private AuditIntegrityService auditIntegrityService;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void rejectsIngestionWithSanitizedServiceUnavailableWhenNoKeysAreConfigured()
            throws Exception {
        webTestClient.post()
                .uri("/audit")
                .header("X-Service-Authorization", "Bearer " + wellFormedCredential())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "action": "LOGIN_SUCCESS",
                          "target": "auth-session"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().isEmpty();

        verifyNoInteractions(auditService);
    }

    private static String wellFormedCredential() throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://dentistdss.internal/services")
                .subject("auth-service")
                .audience("audit-service")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(30)))
                .claim("tokenType", "service")
                .claim("scope", "audit:ingest")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID("auth-service-2026-07")
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey createKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("auth-service-2026-07").generate();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
