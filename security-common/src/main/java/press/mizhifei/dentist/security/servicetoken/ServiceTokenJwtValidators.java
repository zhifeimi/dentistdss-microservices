package press.mizhifei.dentist.security.servicetoken;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Strict claim validation for service credentials, mirroring the hardened
 * GenAI decoder: pinned algorithm/issuer/subject/audience, key-bound scope
 * allow-list, mandatory jti, and a hard 30-second lifetime with zero clock
 * skew tolerance.
 */
public final class ServiceTokenJwtValidators {

    private ServiceTokenJwtValidators() {
    }

    public static OAuth2TokenValidator<Jwt> strict(TrustedServiceKey key, String expectedAudience) {
        return jwt -> validClaims(jwt, key, expectedAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "Invalid service credential",
                        null));
    }

    static boolean validClaims(Jwt jwt, TrustedServiceKey key, String expectedAudience) {
        Instant issuedAt = jwt.getIssuedAt();
        Instant notBefore = jwt.getNotBefore();
        Instant expiresAt = jwt.getExpiresAt();
        Object algorithm = jwt.getHeaders().get("alg");
        Object keyId = jwt.getHeaders().get("kid");
        String scope = jwt.getClaimAsString("scope");
        return "RS256".equals(String.valueOf(algorithm))
                && key.keyId().equals(keyId)
                && ServiceTokenConstants.ISSUER.equals(
                        jwt.getIssuer() == null ? null : jwt.getIssuer().toString())
                && key.subject().equals(jwt.getSubject())
                && jwt.getAudience().size() == 1
                && jwt.getAudience().contains(expectedAudience)
                && ServiceTokenConstants.TOKEN_TYPE.equals(jwt.getClaimAsString("tokenType"))
                && scope != null
                && key.scopes().contains(scope)
                && StringUtils.hasText(jwt.getId())
                && issuedAt != null
                && notBefore != null
                && expiresAt != null
                && !notBefore.isBefore(issuedAt)
                && !notBefore.isAfter(expiresAt)
                && !expiresAt.isAfter(issuedAt.plus(ServiceTokenConstants.MAX_LIFETIME));
    }
}
