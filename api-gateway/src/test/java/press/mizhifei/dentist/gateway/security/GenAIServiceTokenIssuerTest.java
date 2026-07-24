package press.mizhifei.dentist.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import reactor.test.StepVerifier;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GenAIServiceTokenIssuerTest {

    private static final String KEY_ID = "gateway-genai-2026-01";
    private static RSAKey key;

    @BeforeAll
    static void createKey() throws Exception {
        key = new RSAKeyGenerator(2048)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(KEY_ID)
                .generate();
    }

    @Test
    void mintsStrictAudienceScopedRs256Credential() {
        GenAIServiceTokenIssuer issuer = new GenAIServiceTokenIssuer(
                privateKeyPem(key),
                publicKeyPem(key),
                KEY_ID);
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withPublicKey(publicKey(key))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        StepVerifier.create(issuer.issueAnonymousHelpToken().flatMap(decoder::decode))
                .assertNext(jwt -> {
                    assertEquals("RS256", jwt.getHeaders().get("alg"));
                    assertEquals(KEY_ID, jwt.getHeaders().get("kid"));
                    assertEquals("https://api-gateway.dentistdss.internal", jwt.getIssuer().toString());
                    assertEquals("api-gateway", jwt.getSubject());
                    assertEquals(List.of("genai-service"), jwt.getAudience());
                    assertEquals("service", jwt.getClaimAsString("tokenType"));
                    assertEquals("genai:anonymous-help", jwt.getClaimAsString("scope"));
                    assertNotNull(jwt.getId());
                    assertEquals(jwt.getIssuedAt(), jwt.getNotBefore());
                    assertEquals(Duration.ofSeconds(30),
                            Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()));
                })
                .verifyComplete();
    }

    @Test
    void failsClosedWhenSigningConfigurationIsMissingOrMalformed() {
        StepVerifier.create(new GenAIServiceTokenIssuer("", "", "")
                        .issueAnonymousHelpToken())
                .expectErrorMatches(error -> error instanceof org.springframework.web.server.ResponseStatusException
                        && ((org.springframework.web.server.ResponseStatusException) error)
                        .getStatusCode().value() == 503)
                .verify();
        StepVerifier.create(new GenAIServiceTokenIssuer("not-a-key", "not-a-key", KEY_ID)
                        .issueAnonymousHelpToken())
                .expectErrorMatches(error -> error instanceof org.springframework.web.server.ResponseStatusException
                        && ((org.springframework.web.server.ResponseStatusException) error)
                        .getStatusCode().value() == 503)
                .verify();
    }

    private static RSAPublicKey publicKey(RSAKey rsaKey) {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String publicKeyPem(RSAKey rsaKey) {
        return pem("PUBLIC KEY", publicKey(rsaKey).getEncoded());
    }

    private static String privateKeyPem(RSAKey rsaKey) {
        try {
            return pem("PRIVATE KEY", ((RSAPrivateKey) rsaKey.toRSAPrivateKey()).getEncoded());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----";
    }
}
