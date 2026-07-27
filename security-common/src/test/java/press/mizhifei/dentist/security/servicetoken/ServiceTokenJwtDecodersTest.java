package press.mizhifei.dentist.security.servicetoken;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Strict-validation and kid-routing matrix for both decoders. The two decoders
 * share the validator, so the reactive decoder runs the full claim matrix and
 * the servlet decoder covers routing plus the fail-closed states.
 */
class ServiceTokenJwtDecodersTest {

    private static RSAKey appointmentKey;
    private static RSAKey authKey;
    private static RSAKey rogueKey;
    private static List<TrustedServiceKey> trustedKeys;
    private static ServiceTokenReactiveJwtDecoder reactiveDecoder;
    private static ServiceTokenJwtDecoder servletDecoder;

    @BeforeAll
    static void setUpKeys() throws Exception {
        appointmentKey = ServiceTokenTestKeys.generateKey(ServiceTokenTestKeys.APPOINTMENT_KID);
        authKey = ServiceTokenTestKeys.generateKey(ServiceTokenTestKeys.AUTH_KID);
        rogueKey = ServiceTokenTestKeys.generateKey("rogue-kid");
        trustedKeys = List.of(
                new TrustedServiceKey(
                        ServiceTokenTestKeys.APPOINTMENT_KID,
                        ServiceTokenTestKeys.publicKeyPem(appointmentKey),
                        "appointment-service",
                        java.util.Set.of("notification:send")),
                new TrustedServiceKey(
                        ServiceTokenTestKeys.AUTH_KID,
                        ServiceTokenTestKeys.publicKeyPem(authKey),
                        "auth-service",
                        java.util.Set.of("notification:email", "audit:ingest")));
        reactiveDecoder = new ServiceTokenReactiveJwtDecoder(trustedKeys, ServiceTokenTestKeys.AUDIENCE);
        servletDecoder = new ServiceTokenJwtDecoder(trustedKeys, ServiceTokenTestKeys.AUDIENCE);
    }

    @Test
    void acceptsCredentialSignedByTrustedCallerKey() throws Exception {
        String token = ServiceTokenTestKeys.validToken(
                appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                "appointment-service", "notification:send");
        StepVerifier.create(reactiveDecoder.decode(token))
                .assertNext(jwt -> {
                    assertEquals("appointment-service", jwt.getSubject());
                    assertEquals("notification:send", jwt.getClaimAsString("scope"));
                })
                .verifyComplete();
    }

    @Test
    void routesByKidToTheMatchingCallerKey() throws Exception {
        String token = ServiceTokenTestKeys.validToken(
                authKey, ServiceTokenTestKeys.AUTH_KID,
                "auth-service", "notification:email");
        StepVerifier.create(reactiveDecoder.decode(token))
                .assertNext(jwt -> assertEquals("auth-service", jwt.getSubject()))
                .verifyComplete();
        assertEquals("auth-service", servletDecoder.decode(token).getSubject());
    }

    @Test
    void rejectsScopeEscalationBeyondTheKeysGrant() throws Exception {
        // appointment-service's key is valid but may not mint notification:email
        String token = ServiceTokenTestKeys.validToken(
                appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                "appointment-service", "notification:email");
        assertRejected(token);
    }

    @Test
    void rejectsSubjectSpoofingOnAValidKey() throws Exception {
        // appointment-service's key cannot present auth-service's identity
        String token = ServiceTokenTestKeys.validToken(
                appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                "auth-service", "notification:send");
        assertRejected(token);
    }

    @Test
    void rejectsSignatureNotMatchingTheKidKey() throws Exception {
        // rogue key signs with appointment's kid
        String token = ServiceTokenTestKeys.validToken(
                rogueKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                "appointment-service", "notification:send");
        assertRejected(token);
    }

    @Test
    void rejectsUnknownKid() throws Exception {
        String token = ServiceTokenTestKeys.validToken(
                rogueKey, "rogue-kid", "rogue-service", "notification:send");
        StepVerifier.create(reactiveDecoder.decode(token))
                .expectError(BadJwtException.class)
                .verify();
        assertThrows(BadJwtException.class, () -> servletDecoder.decode(token));
    }

    @Test
    void reportsMissingConfigurationAsServiceUnavailable() {
        ServiceTokenReactiveJwtDecoder unconfigured =
                new ServiceTokenReactiveJwtDecoder(List.of(), ServiceTokenTestKeys.AUDIENCE);
        StepVerifier.create(unconfigured.decode("token"))
                .expectError(AuthenticationServiceException.class)
                .verify();
        assertThrows(AuthenticationServiceException.class,
                () -> new ServiceTokenJwtDecoder(List.of(), ServiceTokenTestKeys.AUDIENCE)
                        .decode("token"));
    }

    @Test
    void rejectsMalformedToken() {
        StepVerifier.create(reactiveDecoder.decode("not-a-jwt"))
                .expectError(BadJwtException.class)
                .verify();
        assertThrows(BadJwtException.class, () -> servletDecoder.decode("not-a-jwt"));
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        assertRejected(ServiceTokenTestKeys.token(appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                builder -> builder.issuer("https://evil")));
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        assertRejected(ServiceTokenTestKeys.token(appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                builder -> builder.audience("audit-service")));
    }

    @Test
    void rejectsMultipleAudiences() throws Exception {
        assertRejected(ServiceTokenTestKeys.token(appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                builder -> builder.audience(List.of(ServiceTokenTestKeys.AUDIENCE, "audit-service"))));
    }

    @Test
    void rejectsWrongTokenType() throws Exception {
        assertRejected(ServiceTokenTestKeys.token(appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                builder -> builder.claim("tokenType", "access")));
    }

    @Test
    void rejectsMissingJti() throws Exception {
        assertRejected(ServiceTokenTestKeys.token(appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                builder -> builder.jwtID(null)));
    }

    @Test
    void rejectsExpiredCredential() throws Exception {
        Instant past = Instant.now().minusSeconds(120);
        assertRejected(ServiceTokenTestKeys.token(appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                builder -> builder
                        .issueTime(Date.from(past))
                        .notBeforeTime(Date.from(past))
                        .expirationTime(Date.from(past.plusSeconds(30)))));
    }

    @Test
    void rejectsCredentialLongerThanThirtySeconds() throws Exception {
        Instant now = Instant.now();
        assertRejected(ServiceTokenTestKeys.token(appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                builder -> builder
                        .issueTime(Date.from(now))
                        .notBeforeTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(31)))));
    }

    @Test
    void rejectsNotBeforeAfterIssueTime() throws Exception {
        Instant now = Instant.now();
        assertRejected(ServiceTokenTestKeys.token(appointmentKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                builder -> builder
                        .issueTime(Date.from(now))
                        .notBeforeTime(Date.from(now.plusSeconds(10)))
                        .expirationTime(Date.from(now.plusSeconds(30)))));
    }

    private static void assertRejected(String token) {
        StepVerifier.create(reactiveDecoder.decode(token))
                .expectError()
                .verify();
    }
}
