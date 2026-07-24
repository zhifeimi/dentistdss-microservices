package press.mizhifei.dentist.security.servicetoken;

import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import com.nimbusds.jwt.SignedJWT;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reactive decoder for service credentials, routing by the credential's
 * {@code kid} to the matching trusted caller key. Each key gets its own
 * signature-pinned decoder with strict claim validation (subject and scopes
 * are bound to the key, so a valid signature from one caller cannot present
 * another caller's identity or scopes).
 *
 * <p>Fail-closed: no trusted keys configured → {@link AuthenticationServiceException}
 * (rendered as a sanitized 503 by the shared failure handlers); unknown kid or
 * malformed credential → {@link BadJwtException} (401).</p>
 */
public final class ServiceTokenReactiveJwtDecoder implements ReactiveJwtDecoder {

    private final Map<String, ReactiveJwtDecoder> decodersByKeyId;

    public ServiceTokenReactiveJwtDecoder(List<TrustedServiceKey> trustedKeys, String expectedAudience) {
        this.decodersByKeyId = trustedKeys.stream().collect(Collectors.toUnmodifiableMap(
                TrustedServiceKey::keyId,
                key -> decoderFor(key, expectedAudience),
                (first, duplicate) -> first));
    }

    @Override
    public Mono<Jwt> decode(String token) {
        if (decodersByKeyId.isEmpty()) {
            return Mono.error(new AuthenticationServiceException(
                    "Service authentication is unavailable"));
        }
        String keyId;
        try {
            keyId = SignedJWT.parse(token).getHeader().getKeyID();
        } catch (Exception error) {
            return Mono.error(new BadJwtException("Invalid service credential"));
        }
        ReactiveJwtDecoder delegate = keyId == null ? null : decodersByKeyId.get(keyId);
        if (delegate == null) {
            return Mono.error(new BadJwtException("Unknown service credential"));
        }
        return delegate.decode(token);
    }

    private static ReactiveJwtDecoder decoderFor(TrustedServiceKey key, String expectedAudience) {
        try {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
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
