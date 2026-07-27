package press.mizhifei.dentist.notification.controller;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import press.mizhifei.dentist.notification.config.NotificationSecurityConfig;
import press.mizhifei.dentist.notification.dto.NotificationRequest;
import press.mizhifei.dentist.notification.dto.NotificationResponse;
import press.mizhifei.dentist.notification.service.EmailService;
import press.mizhifei.dentist.notification.service.NotificationService;
import press.mizhifei.dentist.security.ReactiveJwtSecurityAutoConfiguration;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@WebFluxTest({NotificationController.class, EmailController.class})
@ImportAutoConfiguration(ReactiveWebSecurityAutoConfiguration.class)
@Import({
        NotificationSecurityConfig.class,
        ReactiveJwtSecurityAutoConfiguration.class
})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.example/jwks",
        "jwt.issuer=https://issuer.example",
        "jwt.audience=dentistdss-api",
        "springdoc.api-docs.enabled=false",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class NotificationControllerSecurityTest {

    private static final String AUDIENCE = "notification-service";
    private static final String AUTH_KID = "auth-service-2026-07";
    private static final String APPOINTMENT_KID = "appointment-service-2026-07";
    private static final String CLINICAL_RECORDS_KID = "clinical-records-service-2026-07";
    private static final RSAKey AUTH_KEY = createKey(AUTH_KID);
    private static final RSAKey APPOINTMENT_KEY = createKey(APPOINTMENT_KID);
    private static final RSAKey CLINICAL_RECORDS_KEY = createKey(CLINICAL_RECORDS_KID);
    private static final RSAKey ROGUE_KEY = createKey("rogue-kid");

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    @DynamicPropertySource
    static void serviceAuthProperties(DynamicPropertyRegistry registry) {
        registry.add("app.security.service-auth.audience", () -> AUDIENCE);
        registry.add("app.security.service-auth.trusted-keys.auth.key-id", () -> AUTH_KID);
        registry.add("app.security.service-auth.trusted-keys.auth.public-key",
                () -> publicKeyPem(AUTH_KEY));
        registry.add("app.security.service-auth.trusted-keys.auth.subject",
                () -> "auth-service");
        registry.add("app.security.service-auth.trusted-keys.auth.scopes[0]",
                () -> "notification:email");
        registry.add("app.security.service-auth.trusted-keys.appointment.key-id",
                () -> APPOINTMENT_KID);
        registry.add("app.security.service-auth.trusted-keys.appointment.public-key",
                () -> publicKeyPem(APPOINTMENT_KEY));
        registry.add("app.security.service-auth.trusted-keys.appointment.subject",
                () -> "appointment-service");
        registry.add("app.security.service-auth.trusted-keys.appointment.scopes[0]",
                () -> "notification:send");
        registry.add("app.security.service-auth.trusted-keys.clinicalrecords.key-id",
                () -> CLINICAL_RECORDS_KID);
        registry.add("app.security.service-auth.trusted-keys.clinicalrecords.public-key",
                () -> publicKeyPem(CLINICAL_RECORDS_KEY));
        registry.add("app.security.service-auth.trusted-keys.clinicalrecords.subject",
                () -> "clinical-records-service");
        registry.add("app.security.service-auth.trusted-keys.clinicalrecords.scopes[0]",
                () -> "notification:send");
    }

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void usesNamedSharedReactiveJwtDecoderInsteadOfBootDefault() {
        assertTrue(applicationContext.containsBean("dentistDssAccessTokenReactiveJwtDecoder"));
        assertFalse(applicationContext.containsBean("reactiveJwtDecoder"));
        assertFalse(applicationContext.containsBean("jwtDecoder"));
    }

    @Test
    void rejectsNotificationReadWithoutBearerToken() {
        webTestClient.get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    @Test
    void forgedIdentityHeadersDoNotAuthenticateNotificationRead() {
        webTestClient.get()
                .uri("/notification/user/42")
                .header("X-User-ID", "42")
                .header("X-User-Roles", "SYSTEM_ADMIN")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectsCrossUserNotificationRead() {
        userClient("41")
                .get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    @Test
    void systemAdminCannotReadAnotherUsersNotifications() {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt
                        .subject("41")
                        .claim("roles", List.of("SYSTEM_ADMIN"))))
                .get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    @Test
    void allowsOwnerToReadNotifications() {
        when(notificationService.getUserNotifications(42L, 42L))
                .thenReturn(List.of());

        userClient("42")
                .get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isOk();

        verify(notificationService).getUserNotifications(42L, 42L);
    }

    @Test
    void allowsOwnerToReadUnreadCount() {
        when(notificationService.getUnreadCount(42L, 42L)).thenReturn(3L);

        userClient("42")
                .get()
                .uri("/notification/user/42/unread-count")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.dataObject.unreadCount").isEqualTo(3);

        verify(notificationService).getUnreadCount(42L, 42L);
    }

    @Test
    void allowsOwnerToMarkNotificationReadUsingVerifiedSubject() {
        NotificationResponse response = NotificationResponse.builder()
                .id(100L)
                .userId(42L)
                .status("READ")
                .build();
        when(notificationService.markAsRead(100L, 42L)).thenReturn(response);

        userClient("42")
                .put()
                .uri("/notification/100/read")
                .header("X-User-ID", "999")
                .exchange()
                .expectStatus().isOk();

        verify(notificationService).markAsRead(100L, 42L);
    }

    @Test
    void rejectsNonnumericAuthenticatedSubject() {
        userClient("user-42")
                .get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    @Test
    void deniesUserTokenFromCreatingArbitraryNotification() {
        userClient("42")
                .post()
                .uri("/notification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "userId": 42,
                          "type": "IN_APP",
                          "body": "caller-authored notification"
                        }
                        """)
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/notification/email/send",
            "/notification/email/verification",
            "/notification/email/processing-reminder",
            "/notification/email/system-admin-approval",
            "/notification/email/notification"
    })
    void deniesArbitraryEmailEndpointsEvenToSystemAdmin(String path) {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt
                        .subject("1")
                        .claim("roles", List.of("SYSTEM_ADMIN"))))
                .post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(emailService);
    }

    @Test
    void deniesUnlistedRoutesForAuthenticatedUsers() {
        userClient("42")
                .get()
                .uri("/notification/unlisted")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    private WebTestClient userClient(String subject) {
        return webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt
                .subject(subject)
                .claim("roles", List.of("PATIENT"))));
    }

    // ---- service-credential acceptance: POST /notification/send ----

    @Test
    void createsNotificationWithVerifiedAppointmentCredential() throws Exception {
        when(notificationService.createNotification(any())).thenReturn(
                NotificationResponse.builder().id(1L).userId(42L).status("PENDING").build());

        postSend(credential(APPOINTMENT_KEY, APPOINTMENT_KID,
                "appointment-service", "notification:send"))
                .expectStatus().isOk();

        ArgumentCaptor<NotificationRequest> captor =
                ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).createNotification(captor.capture());
        assertEquals(42L, captor.getValue().getUserId());
        assertEquals("IN_APP", captor.getValue().getType());
        assertEquals("Appointment confirmed", captor.getValue().getBody());
    }

    @Test
    void createsNotificationWithVerifiedClinicalRecordsCredential() throws Exception {
        when(notificationService.createNotification(any())).thenReturn(
                NotificationResponse.builder().id(2L).userId(42L).status("PENDING").build());

        postSend(credential(CLINICAL_RECORDS_KEY, CLINICAL_RECORDS_KID,
                "clinical-records-service", "notification:send"))
                .expectStatus().isOk();

        verify(notificationService).createNotification(any());
    }

    @Test
    void rejectsAuthCredentialOnTheSendSurface() throws Exception {
        // auth-service's key is only granted notification:email
        postSend(credential(AUTH_KEY, AUTH_KID, "auth-service", "notification:send"))
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    // ---- service-credential acceptance: POST /notification/email/** ----

    @Test
    void sendsTransactionalEmailWithVerifiedAuthCredential() throws Exception {
        webTestClient.post()
                .uri("/notification/email/send")
                .header("X-Service-Authorization", "Bearer " + credential(
                        AUTH_KEY, AUTH_KID, "auth-service", "notification:email"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "to": "patient@example.com",
                          "subject": "Verify your email",
                          "body": "code 1234",
                          "html": false
                        }
                        """)
                .exchange()
                .expectStatus().isOk();

        verify(emailService).sendEmail("patient@example.com", "Verify your email",
                "code 1234", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/notification/email/verification",
            "/notification/email/processing-reminder",
            "/notification/email/system-admin-approval",
            "/notification/email/notification"
    })
    void acceptsAuthCredentialOnEveryEmailEndpoint(String path) throws Exception {
        webTestClient.post()
                .uri(path)
                .header("X-Service-Authorization", "Bearer " + credential(
                        AUTH_KEY, AUTH_KID, "auth-service", "notification:email"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rejectsAppointmentCredentialOnTheEmailSurface() throws Exception {
        // scope escalation: appointment's key only grants notification:send
        webTestClient.post()
                .uri("/notification/email/send")
                .header("X-Service-Authorization", "Bearer " + credential(
                        APPOINTMENT_KEY, APPOINTMENT_KID,
                        "appointment-service", "notification:email"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(emailService);
    }

    // ---- service-credential rejection matrix ----

    @Test
    void rejectsCredentialForTheWrongAudience() throws Exception {
        postSend(credential(APPOINTMENT_KEY, APPOINTMENT_KID,
                "appointment-service", "notification:send", "audit-service"))
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectsCredentialWithAnUnknownKid() throws Exception {
        postSend(credential(ROGUE_KEY, "rogue-kid",
                "appointment-service", "notification:send"))
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectsCredentialSignedByAnotherKeyUnderATrustedKid() throws Exception {
        // rogue key claims appointment's kid: signature fails against the key
        // bound to that kid
        postSend(credential(ROGUE_KEY, APPOINTMENT_KID,
                "appointment-service", "notification:send"))
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectsCredentialWithAForgedSubjectUnderItsOwnKid() throws Exception {
        // appointment's key cannot impersonate auth-service: subject is bound
        // to the kid
        postSend(credential(APPOINTMENT_KEY, APPOINTMENT_KID,
                "auth-service", "notification:send"))
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    @Test
    void serviceCredentialIsIgnoredOnUserReadRoutes() throws Exception {
        webTestClient.get()
                .uri("/notification/user/42")
                .header("X-Service-Authorization", "Bearer " + credential(
                        APPOINTMENT_KEY, APPOINTMENT_KID,
                        "appointment-service", "notification:send"))
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    private WebTestClient.ResponseSpec postSend(String token) {
        return webTestClient.post()
                .uri("/notification/send")
                .header("X-Service-Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "userId": 42,
                          "type": "IN_APP",
                          "body": "Appointment confirmed"
                        }
                        """)
                .exchange();
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
