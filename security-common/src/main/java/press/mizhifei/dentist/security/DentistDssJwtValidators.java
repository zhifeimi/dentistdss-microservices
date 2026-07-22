package press.mizhifei.dentist.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.util.StringUtils;

import java.util.List;

public final class DentistDssJwtValidators {

    private DentistDssJwtValidators() {
    }

    public static OAuth2TokenValidator<Jwt> create(String issuer, String audience) {
        if (!StringUtils.hasText(issuer)) {
            throw new IllegalStateException("JWT issuer must be configured");
        }
        if (!StringUtils.hasText(audience)) {
            throw new IllegalStateException("JWT audience must be configured");
        }

        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud",
                audiences -> audiences != null && audiences.contains(audience));
        OAuth2TokenValidator<Jwt> tokenTypeValidator = new JwtClaimValidator<String>(
                "tokenType",
                "access"::equals);
        OAuth2TokenValidator<Jwt> tokenIdValidator = new JwtClaimValidator<String>(
                "jti",
                StringUtils::hasText);
        OAuth2TokenValidator<Jwt> subjectValidator = new JwtClaimValidator<String>(
                "sub",
                DentistDssJwtValidators::isValidUserSubject);
        OAuth2TokenValidator<Jwt> securityVersionValidator = new JwtClaimValidator<Object>(
                "securityVersion",
                AccessTokenStateSupport::isPositiveIntegralClaim);
        OAuth2TokenValidator<Jwt> sessionFamilyValidator = new JwtClaimValidator<Object>(
                "sessionFamilyId",
                AccessTokenStateSupport::isNonBlankString);

        return new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator,
                tokenTypeValidator,
                tokenIdValidator,
                subjectValidator,
                securityVersionValidator,
                sessionFamilyValidator);
    }

    private static boolean isValidUserSubject(String subject) {
        if (!StringUtils.hasText(subject)) {
            return false;
        }
        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
