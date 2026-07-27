package press.mizhifei.dentist.security;

import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

final class AccessTokenStateSupport {

    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
    private static final String KEY_PREFIX = "security:access:v1:";

    private AccessTokenStateSupport() {
    }

    static boolean isPositiveIntegralClaim(Object value) {
        if (value == null) {
            return false;
        }
        try {
            return positiveIntegral(value) > 0;
        } catch (ArithmeticException | IllegalArgumentException ex) {
            return false;
        }
    }

    static boolean isNonBlankString(Object value) {
        return value instanceof String text && StringUtils.hasText(text);
    }

    static AccessTokenClaims claims(Jwt jwt) {
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw invalidToken();
        }

        long userId;
        try {
            userId = Long.parseLong(subject);
        } catch (NumberFormatException ex) {
            throw invalidToken();
        }
        if (userId <= 0) {
            throw invalidToken();
        }

        long securityVersion;
        try {
            securityVersion = positiveIntegral(jwt.getClaim("securityVersion"));
        } catch (ArithmeticException | IllegalArgumentException ex) {
            throw invalidToken();
        }
        if (securityVersion <= 0) {
            throw invalidToken();
        }

        Object familyClaim = jwt.getClaim("sessionFamilyId");
        if (!(familyClaim instanceof String sessionFamilyId)
                || !StringUtils.hasText(sessionFamilyId)) {
            throw invalidToken();
        }

        return new AccessTokenClaims(Long.toString(userId), securityVersion, sessionFamilyId);
    }

    static List<String> keys(AccessTokenClaims claims) {
        String hashTag = "{" + claims.userId() + "}";
        return List.of(
                KEY_PREFIX + hashTag + ":account",
                KEY_PREFIX + hashTag + ":family:" + claims.sessionFamilyId());
    }

    static void validate(List<String> values, long tokenSecurityVersion) {
        if (values == null || values.size() != 2) {
            throw stateUnavailable(null);
        }

        AccountState accountState = parseAccountState(values.get(0));
        FamilyState familyState = parseFamilyState(values.get(1));
        if (!accountState.active()
                || accountState.securityVersion() != tokenSecurityVersion
                || familyState == FamilyState.REVOKED) {
            throw invalidToken();
        }
    }

    static BadJwtException invalidToken() {
        return new BadJwtException("Invalid access token");
    }

    static AuthenticationServiceException stateUnavailable(Throwable cause) {
        String message = "Access-token security state unavailable";
        return cause == null
                ? new AuthenticationServiceException(message)
                : new AuthenticationServiceException(message, cause);
    }

    private static long positiveIntegral(Object value) {
        return switch (value) {
            case Byte number -> number.longValue();
            case Short number -> number.longValue();
            case Integer number -> number.longValue();
            case Long number -> number;
            case BigInteger number -> number.longValueExact();
            case BigDecimal number -> number.longValueExact();
            default -> throw new IllegalArgumentException("Claim is not an integral number");
        };
    }

    private static AccountState parseAccountState(String value) {
        if (!StringUtils.hasText(value)) {
            throw stateUnavailable(null);
        }

        String[] parts = value.split(":", -1);
        if (parts.length != 2
                || !POSITIVE_DECIMAL.matcher(parts[0]).matches()
                || !(parts[1].equals("0") || parts[1].equals("1"))) {
            throw stateUnavailable(null);
        }

        try {
            return new AccountState(parts[1].equals("1"), Long.parseLong(parts[0]));
        } catch (NumberFormatException ex) {
            throw stateUnavailable(ex);
        }
    }

    private static FamilyState parseFamilyState(String value) {
        if (!StringUtils.hasText(value)) {
            throw stateUnavailable(null);
        }
        return switch (value) {
            case "active" -> FamilyState.ACTIVE;
            case "revoked" -> FamilyState.REVOKED;
            default -> throw stateUnavailable(null);
        };
    }

    record AccessTokenClaims(String userId, long securityVersion, String sessionFamilyId) {
    }

    private record AccountState(boolean active, long securityVersion) {
    }

    private enum FamilyState {
        ACTIVE,
        REVOKED
    }
}
