package press.mizhifei.dentist.security.servicetoken;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Base64URL;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Mints audience-scoped service credentials for one calling service. Each
 * service that makes credentialed internal calls owns an RSA key pair; the
 * subject of every credential is that service's registered name, giving
 * targets a cryptographically verified actor for server-side attribution.
 *
 * <p>Fail-closed: if the private key, key id, or service name is not
 * configured, {@link #issue} throws {@link ServiceTokenConfigurationException}
 * rather than producing an unsigned or misattributed credential.</p>
 */
public final class ServiceTokenIssuer {

    private final JwtEncoder encoder;
    private final String keyId;
    private final String serviceName;
    private final Clock clock;

    public ServiceTokenIssuer(String privateKeyPem, String keyId, String serviceName) {
        this(buildEncoder(privateKeyPem, keyId), keyId, serviceName, Clock.systemUTC());
    }

    ServiceTokenIssuer(JwtEncoder encoder, String keyId, String serviceName, Clock clock) {
        this.encoder = encoder;
        this.keyId = keyId;
        this.serviceName = serviceName;
        this.clock = clock;
    }

    /**
     * Issues a fresh 30-second credential for one call.
     *
     * @param audience the target service's registered name (its {@code aud})
     * @param scope    the endpoint scope the target must accept for this call
     */
    public String issue(String audience, String scope) {
        if (encoder == null || !StringUtils.hasText(keyId) || !StringUtils.hasText(serviceName)) {
            throw new ServiceTokenConfigurationException(
                    "Service credentials are not configured for this service");
        }
        if (!StringUtils.hasText(audience) || !StringUtils.hasText(scope)) {
            throw new IllegalArgumentException("audience and scope are required");
        }
        Instant now = clock.instant();
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keyId)
                .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ServiceTokenConstants.ISSUER)
                .subject(serviceName)
                .audience(List.of(audience))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(ServiceTokenConstants.MAX_LIFETIME))
                .claim("tokenType", ServiceTokenConstants.TOKEN_TYPE)
                .claim("scope", scope)
                .build();
        try {
            return encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
        } catch (JwtEncodingException error) {
            throw new ServiceTokenConfigurationException(
                    "Unable to encode service credential: " + error.getMessage());
        }
    }

    private static JwtEncoder buildEncoder(String privateKeyPem, String keyId) {
        if (!StringUtils.hasText(privateKeyPem) || !StringUtils.hasText(keyId)) {
            return null;
        }
        try {
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decodePem(privateKeyPem)));
            // Nimbus builds RSA keys from public components; derive them from
            // the private CRT parameters (present in every PKCS#8 RSA key we
            // provision). A non-CRT private key cannot sign through the
            // encoder, so treat it as unconfigured (fail closed).
            if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
                return null;
            }
            RSAKey rsaKey = new RSAKey.Builder(
                    Base64URL.encode(crtKey.getModulus()),
                    Base64URL.encode(crtKey.getPublicExponent()))
                    .privateKey(crtKey)
                    .keyID(keyId)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(
                    new JWKSet(rsaKey)));
        } catch (Exception error) {
            return null;
        }
    }

    private static byte[] decodePem(String pem) {
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }
}
