package press.mizhifei.dentist.security.servicetoken;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.function.Consumer;

/** Hand-built JWT fixtures for service-token tests, mirroring the GenAI decoder tests. */
final class ServiceTokenTestKeys {

    static final String AUDIENCE = "notification-service";
    static final String APPOINTMENT_KID = "appointment-service-2026-07";
    static final String AUTH_KID = "auth-service-2026-07";

    private ServiceTokenTestKeys() {
    }

    static RSAKey generateKey(String kid) throws Exception {
        return new RSAKeyGenerator(2048).keyID(kid).generate();
    }

    static String publicKeyPem(RSAKey key) throws Exception {
        byte[] encoded = ((RSAPublicKey) key.toRSAPublicKey()).getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----";
    }

    static String privateKeyPem(RSAKey key) throws Exception {
        byte[] encoded = ((RSAPrivateKey) key.toRSAPrivateKey()).getEncoded();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----";
    }

    static String validToken(RSAKey signingKey, String kid, String subject, String scope) throws Exception {
        return token(signingKey, kid, builder -> builder.subject(subject).claim("scope", scope));
    }

    /** Mints a credential for a non-default audience ({@code aud} is replaced, not appended). */
    static String validToken(RSAKey signingKey, String kid, String subject, String audience, String scope)
            throws Exception {
        return token(signingKey, kid, builder -> builder
                .subject(subject)
                .claim("aud", java.util.List.of(audience))
                .claim("scope", scope));
    }

    static String token(RSAKey signingKey, String kid, Consumer<JWTClaimsSet.Builder> customization)
            throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ServiceTokenConstants.ISSUER)
                .subject("appointment-service")
                .audience(AUDIENCE)
                .jwtID("service-jti")
                .issueTime(Date.from(now.minusSeconds(1)))
                .notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(29)))
                .claim("tokenType", ServiceTokenConstants.TOKEN_TYPE)
                .claim("scope", "notification:send");
        customization.accept(claims);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(kid)
                        .build(),
                claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
}
