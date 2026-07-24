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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import press.mizhifei.dentist.audit.config.AuditSecurityConfig;
import press.mizhifei.dentist.audit.dto.AuditEntryRequest;
import press.mizhifei.dentist.audit.dto.AuditEntryResponse;
import press.mizhifei.dentist.audit.dto.IntegrityReport;
import press.mizhifei.dentist.audit.service.AuditIntegrityService;
import press.mizhifei.dentist.audit.service.AuditService;
import press.mizhifei.dentist.security.ReactiveJwtSecurityAutoConfiguration;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Security matrix for audit ingestion and reads. Ingestion now requires a
 * verified service credential with the {@code audit:ingest} scope and records
 * the credential's subject as the actor; SYSTEM_ADMIN user JWTs can only read.
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
class AuditControllerSecurityTest {

    private static final String AUDIENCE = "audit-service";
    private static final String AUTH_KID = "auth-service-2026-07";
    private static final RSAKey AUTH_KEY = createKey(AUTH_KID);
    private static final RSAKey ROGUE_KEY = createKey("rogue-kid");

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private AuditIntegrityService auditIntegrityService;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void serviceAuthProperties(DynamicPropertyRegistry registry) {
        registry.add("app.security.service-auth.audience", () -> AUDIENCE);
        registry.add("app.security.service-auth.trusted-keys.auth.key-id", () -> AUTH_KID);
        registry.add("app.security.service-auth.trusted-keys.auth.public-key",
                () -> publicKeyPem(AUTH_KEY));
        registry.add("app.security.service-auth.trusted-keys.auth.subject",
                () -> "auth-service");
        registry.add("app.security.service-auth.trusted-keys.auth.scopes[0]",
                () -> "audit:ingest");
    }

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
    }

    // ---- reads (GET /audit) keep the SYSTEM_ADMIN user-JWT contract ----

    @Test
    void rejectsRequestWithoutBearerToken() {
        webTestClient.get()
                .uri("/audit")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditService);
    }

    @Test
    void forgedRoleHeaderDoesNotAuthenticateRequest() {
        webTestClient.get()
                .uri("/audit")
                .header("X-User-Roles", "SYSTEM_ADMIN")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditService);
    }

    @Test
    void rejectsAuthenticatedUserWithoutSystemAdminRole() {
        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_PATIENT")))
                .get()
                .uri("/audit")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(auditService);
    }

    @Test
    void allowsSystemAdminToListAuditEntries() {
        when(auditService.listAll()).thenReturn(List.of());

        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .get()
                .uri("/audit")
                .exchange()
                .expectStatus().isOk();

        verify(auditService).listAll();
    }

    // ---- integrity verification (GET /audit/integrity) is SYSTEM_ADMIN only ----

    @Test
    void allowsSystemAdminToVerifyAuditIntegrity() {
        when(auditIntegrityService.verify()).thenReturn(IntegrityReport.builder()
                .verified(true)
                .sealsChecked(2)
                .documentsChecked(4)
                .build());

        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .get()
                .uri("/audit/integrity")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.dataObject.verified").isEqualTo(true)
                .jsonPath("$.dataObject.sealsChecked").isEqualTo(2)
                .jsonPath("$.dataObject.documentsChecked").isEqualTo(4);

        verify(auditIntegrityService).verify();
    }

    @Test
    void rejectsNonAdminIntegrityVerification() {
        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_PATIENT")))
                .get()
                .uri("/audit/integrity")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(auditIntegrityService);
    }

    @Test
    void rejectsAnonymousIntegrityVerification() {
        webTestClient.get()
                .uri("/audit/integrity")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditIntegrityService);
    }

    // ---- ingestion (POST /audit) is service-credential only ----

    @Test
    void recordsIngestionWithVerifiedServiceCredentialAndServerAttributedActor()
            throws Exception {
        when(auditService.record(any(), eq("auth-service"))).thenReturn(
                AuditEntryResponse.builder().id("audit-1").actor("auth-service").build());

        webTestClient.post()
                .uri("/audit")
                .header("X-Service-Authorization", "Bearer " + validCredential())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "action": "LOGIN_SUCCESS",
                          "target": "auth-session",
                          "assertedUserId": 42,
                          "assertedClinicId": 7
                        }
                        """)
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<AuditEntryRequest> captor =
                ArgumentCaptor.forClass(AuditEntryRequest.class);
        verify(auditService).record(captor.capture(), eq("auth-service"));
        assertEquals("LOGIN_SUCCESS", captor.getValue().getAction());
        assertEquals(42L, captor.getValue().getAssertedUserId());
        assertEquals(7L, captor.getValue().getAssertedClinicId());
    }

    @Test
    void systemAdminUserJwtCanNoLongerIngestAuditEntries() {
        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .post()
                .uri("/audit")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "action": "READ",
                          "target": "record-1"
                        }
                        """)
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(auditService);
    }

    @Test
    void rejectsNonAdminUserJwtIngestion() {
        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_DENTIST")))
                .post()
                .uri("/audit")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "action": "READ",
                          "target": "record-1"
                        }
                        """)
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(auditService);
    }

    @Test
    void rejectsCredentialWithScopeBeyondTheKeysGrant() throws Exception {
        String escalated = credential(AUTH_KEY, AUTH_KID, "auth-service",
                "notification:email");

        postCredential(escalated)
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditService);
    }

    @Test
    void rejectsCredentialForTheWrongAudience() throws Exception {
        String misaddressed = credential(AUTH_KEY, AUTH_KID, "auth-service",
                "audit:ingest", "appointment-service");

        postCredential(misaddressed)
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditService);
    }

    @Test
    void rejectsCredentialWithAnUnknownKid() throws Exception {
        String unknownKid = credential(ROGUE_KEY, "rogue-kid", "auth-service",
                "audit:ingest");

        postCredential(unknownKid)
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditService);
    }

    @Test
    void rejectsCredentialSignedByAnotherKeyUnderATrustedKid() throws Exception {
        // rogue key claims auth-service's kid: signature must fail against the
        // key bound to that kid
        String forged = credential(ROGUE_KEY, AUTH_KID, "auth-service",
                "audit:ingest");

        postCredential(forged)
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditService);
    }

    @Test
    void serviceCredentialIsIgnoredOnReadRoutes() throws Exception {
        webTestClient.get()
                .uri("/audit")
                .header("X-Service-Authorization", "Bearer " + validCredential())
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditService);
    }

    private WebTestClient.ResponseSpec postCredential(String token) {
        return webTestClient.post()
                .uri("/audit")
                .header("X-Service-Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "action": "READ",
                          "target": "record-1"
                        }
                        """)
                .exchange();
    }

    private static String validCredential() throws Exception {
        return credential(AUTH_KEY, AUTH_KID, "auth-service", "audit:ingest");
    }

    private static String credential(RSAKey signingKey, String kid, String subject,
            String scope) throws Exception {
        return credential(signingKey, kid, subject, scope, AUDIENCE);
    }

    private static String credential(RSAKey signingKey, String kid, String subject,
            String scope, String audience) throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://dentistdss.internal/services")
                .subject(subject)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(30)))
                .claim("tokenType", "service")
                .claim("scope", scope)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(kid)
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static RSAKey createKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
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
