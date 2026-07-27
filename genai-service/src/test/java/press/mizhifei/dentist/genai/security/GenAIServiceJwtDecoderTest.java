package press.mizhifei.dentist.genai.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import reactor.test.StepVerifier;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenAIServiceJwtDecoderTest {

    private static final String KEY_ID = "gateway-genai-2026-01";
    private static RSAKey trustedKey;
    private static RSAKey untrustedKey;
    private static GenAIServiceJwtDecoder decoder;

    @BeforeAll
    static void setUpKeys() throws Exception {
        trustedKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        untrustedKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        decoder = new GenAIServiceJwtDecoder(publicKeyPem(trustedKey), KEY_ID);
    }

    @Test
    void acceptsStrictThirtySecondAnonymousHelpCredential() throws Exception {
        StepVerifier.create(decoder.decode(token(builder -> { }, trustedKey, KEY_ID)))
                .assertNext(jwt -> {
                    assertEquals("https://api-gateway.dentistdss.internal", jwt.getIssuer().toString());
                    assertEquals("api-gateway", jwt.getSubject());
                    assertEquals(List.of("genai-service"), jwt.getAudience());
                    assertEquals("service", jwt.getClaimAsString("tokenType"));
                    assertEquals("genai:anonymous-help", jwt.getClaimAsString("scope"));
                    assertEquals(KEY_ID, jwt.getHeaders().get("kid"));
                })
                .verifyComplete();
    }

    @Test
    void reportsMissingConfigurationAsServiceUnavailable() {
        StepVerifier.create(new GenAIServiceJwtDecoder("", "").decode("token"))
                .expectError(AuthenticationServiceException.class)
                .verify();
    }

    @Test
    void rejectsExpiredCredential() throws Exception {
        Instant now = Instant.now();
        assertRejected(tokenAt(now.minusSeconds(40), now.minusSeconds(10), trustedKey, KEY_ID,
                builder -> { }));
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        assertRejected(token(builder -> builder.issuer("wrong"), trustedKey, KEY_ID));
    }

    @Test
    void rejectsWrongSubject() throws Exception {
        assertRejected(token(builder -> builder.subject("wrong"), trustedKey, KEY_ID));
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        assertRejected(token(builder -> builder.audience("wrong"), trustedKey, KEY_ID));
    }

    @Test
    void rejectsWrongTokenType() throws Exception {
        assertRejected(token(builder -> builder.claim("tokenType", "access"), trustedKey, KEY_ID));
    }

    @Test
    void rejectsWrongScope() throws Exception {
        assertRejected(token(builder -> builder.claim("scope", "genai:other"), trustedKey, KEY_ID));
    }

    @Test
    void rejectsWrongKeyId() throws Exception {
        assertRejected(token(builder -> { }, trustedKey, "wrong-kid"));
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        assertRejected(token(builder -> { }, untrustedKey, KEY_ID));
    }

    @Test
    void rejectsCredentialLongerThanThirtySeconds() throws Exception {
        Instant now = Instant.now();
        assertRejected(tokenAt(now, now.plusSeconds(31), trustedKey, KEY_ID, builder -> { }));
    }

    @Test
    void rejectsMissingJti() throws Exception {
        assertRejected(token(builder -> builder.jwtID(null), trustedKey, KEY_ID));
    }

    private static String token(
            Consumer<JWTClaimsSet.Builder> customization,
            RSAKey signingKey,
            String keyId) throws Exception {
        Instant now = Instant.now();
        return tokenAt(now.minusSeconds(1), now.plusSeconds(29), signingKey, keyId, customization);
    }

    private static String tokenAt(
            Instant issuedAt,
            Instant expiresAt,
            RSAKey signingKey,
            String keyId,
            Consumer<JWTClaimsSet.Builder> customization) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://api-gateway.dentistdss.internal")
                .subject("api-gateway")
                .audience("genai-service")
                .jwtID("service-jti")
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("tokenType", "service")
                .claim("scope", "genai:anonymous-help");
        customization.accept(claims);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(keyId)
                        .build(),
                claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static String publicKeyPem(RSAKey key) throws Exception {
        byte[] encoded = ((RSAPublicKey) key.toRSAPublicKey()).getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----";
    }

    private static void assertRejected(String token) {
        StepVerifier.create(decoder.decode(token))
                .expectError()
                .verify();
    }
}
