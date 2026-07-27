package press.mizhifei.dentist.security.servicetoken;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servlet counterpart of {@link ServiceTokenReactiveJwtDecoder}: kid-routed,
 * signature-pinned, strict-claim validation of service credentials for MVC
 * targets (currently user-profile-service contact reads). Same fail-closed
 * semantics: unconfigured → {@link AuthenticationServiceException} (sanitized
 * 503); unknown kid / malformed → {@link BadJwtException} (401).
 */
public final class ServiceTokenJwtDecoder implements JwtDecoder {

    private final Map<String, JwtDecoder> decodersByKeyId;

    public ServiceTokenJwtDecoder(List<TrustedServiceKey> trustedKeys, String expectedAudience) {
        this.decodersByKeyId = trustedKeys.stream().collect(Collectors.toUnmodifiableMap(
                TrustedServiceKey::keyId,
                key -> decoderFor(key, expectedAudience),
                (first, duplicate) -> first));
    }

    @Override
    public Jwt decode(String token) {
        if (decodersByKeyId.isEmpty()) {
            throw new AuthenticationServiceException("Service authentication is unavailable");
        }
        String keyId;
        try {
            keyId = SignedJWT.parse(token).getHeader().getKeyID();
        } catch (Exception error) {
            throw new BadJwtException("Invalid service credential");
        }
        JwtDecoder delegate = keyId == null ? null : decodersByKeyId.get(keyId);
        if (delegate == null) {
            throw new BadJwtException("Unknown service credential");
        }
        return delegate.decode(token);
    }

    private static JwtDecoder decoderFor(TrustedServiceKey key, String expectedAudience) {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withPublicKey(TrustedServiceKey.parseRsaPublicKey(key.publicKeyPem()))
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(Duration.ZERO),
                    ServiceTokenJwtValidators.strict(key, expectedAudience)));
            return decoder;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Invalid trusted service key '" + key.keyId() + "'", error);
        }
    }
}
