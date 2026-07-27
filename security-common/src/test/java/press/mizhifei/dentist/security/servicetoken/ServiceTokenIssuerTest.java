package press.mizhifei.dentist.security.servicetoken;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTokenIssuerTest {

    private static RSAKey key;
    private static ServiceTokenIssuer issuer;

    @BeforeAll
    static void setUp() throws Exception {
        key = ServiceTokenTestKeys.generateKey(ServiceTokenTestKeys.APPOINTMENT_KID);
        issuer = new ServiceTokenIssuer(
                ServiceTokenTestKeys.privateKeyPem(key),
                ServiceTokenTestKeys.APPOINTMENT_KID,
                "appointment-service");
    }

    @Test
    void issuesStrictThirtySecondCredentialWithVerifiedSignature() throws Exception {
        String token = issuer.issue("notification-service", "notification:send");
        SignedJWT jwt = SignedJWT.parse(token);

        assertTrue(jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())),
                "credential must verify against the issuer's public key");
        assertEquals("RS256", jwt.getHeader().getAlgorithm().getName());
        assertEquals(ServiceTokenTestKeys.APPOINTMENT_KID, jwt.getHeader().getKeyID());
        assertEquals(ServiceTokenConstants.ISSUER, jwt.getJWTClaimsSet().getIssuer());
        assertEquals("appointment-service", jwt.getJWTClaimsSet().getSubject());
        assertEquals(java.util.List.of("notification-service"),
                jwt.getJWTClaimsSet().getAudience());
        assertEquals("notification:send", jwt.getJWTClaimsSet().getStringClaim("scope"));
        assertEquals("service", jwt.getJWTClaimsSet().getStringClaim("tokenType"));
        assertNotNull(jwt.getJWTClaimsSet().getJWTID());

        Date issuedAt = jwt.getJWTClaimsSet().getIssueTime();
        Date notBefore = jwt.getJWTClaimsSet().getNotBeforeTime();
        Date expiresAt = jwt.getJWTClaimsSet().getExpirationTime();
        assertEquals(issuedAt, notBefore, "nbf must equal iat");
        assertEquals(issuedAt.getTime() + 30_000, expiresAt.getTime(),
                "credentials live exactly 30 seconds");
    }

    @Test
    void failsClosedWhenKeyMaterialIsMissing() {
        assertThrows(ServiceTokenConfigurationException.class,
                () -> new ServiceTokenIssuer("", "", "svc").issue("aud", "scope"));
        assertThrows(ServiceTokenConfigurationException.class,
                () -> new ServiceTokenIssuer("", "kid", "svc").issue("aud", "scope"));
        assertThrows(ServiceTokenConfigurationException.class,
                () -> new ServiceTokenIssuer("-----BEGIN PRIVATE KEY-----\nZm9v\n-----END PRIVATE KEY-----",
                        "kid", "svc").issue("aud", "scope"));
        assertThrows(ServiceTokenConfigurationException.class,
                () -> new ServiceTokenIssuer(ServiceTokenTestKeys.privateKeyPem(key), "kid", "")
                        .issue("aud", "scope"));
    }

    @Test
    void rejectsBlankAudienceOrScope() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> issuer.issue("", "scope"));
        assertThrows(IllegalArgumentException.class, () -> issuer.issue("aud", ""));
    }
}
