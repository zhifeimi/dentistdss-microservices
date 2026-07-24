package press.mizhifei.dentist.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Issues narrowly scoped gateway credentials for anonymous GenAI help. */
@Component
public final class GenAIServiceTokenIssuer {

    public static final String HEADER_NAME = "X-Gateway-Service-Authorization";
    public static final String ISSUER = "https://api-gateway.dentistdss.internal";
    public static final String SUBJECT = "api-gateway";
    public static final String AUDIENCE = "genai-service";
    public static final String TOKEN_TYPE = "service";
    public static final String SCOPE = "genai:anonymous-help";
    static final Duration TOKEN_LIFETIME = Duration.ofSeconds(30);

    private final JwtEncoder encoder;
    private final String keyId;
    private final Clock clock;

    @Autowired
    public GenAIServiceTokenIssuer(
            @Value("${app.security.genai-service-auth.private-key:}") String privateKeyPem,
            @Value("${app.security.genai-service-auth.public-key:}") String publicKeyPem,
            @Value("${app.security.genai-service-auth.key-id:}") String keyId) {
        this(buildEncoder(privateKeyPem, publicKeyPem, keyId), keyId, Clock.systemUTC());
    }

    GenAIServiceTokenIssuer(JwtEncoder encoder, String keyId, Clock clock) {
        this.encoder = encoder;
        this.keyId = keyId;
        this.clock = clock;
    }

    public Mono<String> issueAnonymousHelpToken() {
        if (encoder == null || !StringUtils.hasText(keyId)) {
            return unavailable();
        }
        return Mono.fromCallable(this::encode)
                .onErrorMap(error -> !(error instanceof ResponseStatusException),
                        error -> unavailableException());
    }

    private String encode() {
        Instant now = clock.instant();
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keyId)
                .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(SUBJECT)
                .audience(List.of(AUDIENCE))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(TOKEN_LIFETIME))
                .claim("tokenType", TOKEN_TYPE)
                .claim("scope", SCOPE)
                .build();
        try {
            return encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
        } catch (JwtEncodingException error) {
            throw unavailableException();
        }
    }

    private static JwtEncoder buildEncoder(
            String privateKeyPem,
            String publicKeyPem,
            String keyId) {
        if (!StringUtils.hasText(privateKeyPem)
                || !StringUtils.hasText(publicKeyPem)
                || !StringUtils.hasText(keyId)) {
            return null;
        }
        try {
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decodePem(
                            privateKeyPem,
                            "PRIVATE KEY")));
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodePem(
                            publicKeyPem,
                            "PUBLIC KEY")));
            RSAKey rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keyId)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(
                    new JWKSet(rsaKey)));
        } catch (Exception error) {
            return null;
        }
    }

    private static byte[] decodePem(String pem, String type) {
        String normalized = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    private static <T> Mono<T> unavailable() {
        return Mono.error(unavailableException());
    }

    private static ResponseStatusException unavailableException() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Anonymous GenAI authentication is temporarily unavailable");
    }
}
