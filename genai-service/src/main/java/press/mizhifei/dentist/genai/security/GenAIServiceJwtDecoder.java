package press.mizhifei.dentist.genai.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Strict decoder for the gateway's anonymous-help service credential. */
@Component
public final class GenAIServiceJwtDecoder implements ReactiveJwtDecoder {

    public static final String HEADER_NAME = "X-Gateway-Service-Authorization";
    public static final String AUTHORITY = "SERVICE_GENAI_ANONYMOUS_HELP";
    static final String ISSUER = "https://api-gateway.dentistdss.internal";
    static final String SUBJECT = "api-gateway";
    static final String AUDIENCE = "genai-service";
    static final String TOKEN_TYPE = "service";
    static final String SCOPE = "genai:anonymous-help";
    static final Duration MAX_LIFETIME = Duration.ofSeconds(30);

    private final ReactiveJwtDecoder delegate;

    @Autowired
    public GenAIServiceJwtDecoder(
            @Value("${app.security.genai-service-auth.public-key:}") String publicKeyPem,
            @Value("${app.security.genai-service-auth.key-id:}") String expectedKeyId) {
        this.delegate = buildDecoder(publicKeyPem, expectedKeyId);
    }

    GenAIServiceJwtDecoder(ReactiveJwtDecoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Jwt> decode(String token) {
        if (delegate == null) {
            return Mono.error(new AuthenticationServiceException(
                    "Service authentication is unavailable"));
        }
        return delegate.decode(token);
    }

    private static ReactiveJwtDecoder buildDecoder(String publicKeyPem, String expectedKeyId) {
        if (!StringUtils.hasText(publicKeyPem) || !StringUtils.hasText(expectedKeyId)) {
            return null;
        }
        try {
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodePem(publicKeyPem)));
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey)
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            JwtTimestampValidator timestampValidator = new JwtTimestampValidator(Duration.ZERO);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    timestampValidator,
                    serviceClaimsValidator(expectedKeyId)));
            return decoder;
        } catch (Exception error) {
            return null;
        }
    }

    private static OAuth2TokenValidator<Jwt> serviceClaimsValidator(String expectedKeyId) {
        return jwt -> validClaims(jwt, expectedKeyId)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "Invalid service credential",
                        null));
    }

    private static boolean validClaims(Jwt jwt, String expectedKeyId) {
        Instant issuedAt = jwt.getIssuedAt();
        Instant notBefore = jwt.getNotBefore();
        Instant expiresAt = jwt.getExpiresAt();
        Object algorithm = jwt.getHeaders().get("alg");
        Object keyId = jwt.getHeaders().get("kid");
        return "RS256".equals(String.valueOf(algorithm))
                && expectedKeyId.equals(keyId)
                && ISSUER.equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())
                && SUBJECT.equals(jwt.getSubject())
                && jwt.getAudience().size() == 1
                && jwt.getAudience().contains(AUDIENCE)
                && TOKEN_TYPE.equals(jwt.getClaimAsString("tokenType"))
                && SCOPE.equals(jwt.getClaimAsString("scope"))
                && StringUtils.hasText(jwt.getId())
                && issuedAt != null
                && notBefore != null
                && expiresAt != null
                && !notBefore.isBefore(issuedAt)
                && !notBefore.isAfter(expiresAt)
                && !expiresAt.isAfter(issuedAt.plus(MAX_LIFETIME));
    }

    private static byte[] decodePem(String pem) {
        String normalized = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }
}
