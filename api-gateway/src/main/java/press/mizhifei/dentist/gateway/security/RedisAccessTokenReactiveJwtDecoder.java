package press.mizhifei.dentist.gateway.security;

import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Adds fail-closed current-account and session-family checks after local JWT
 * signature and claim validation.
 *
 * <p>Redis account values use {@code <securityVersion>:1} for active or
 * {@code <securityVersion>:0} for inactive. Family values use {@code active}
 * or {@code revoked}.</p>
 */
public final class RedisAccessTokenReactiveJwtDecoder implements ReactiveJwtDecoder {

    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
    private static final String KEY_PREFIX = "security:access:v1:";

    private final ReactiveJwtDecoder delegate;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final Duration redisTimeout;

    public RedisAccessTokenReactiveJwtDecoder(
            ReactiveJwtDecoder delegate,
            ReactiveStringRedisTemplate redisTemplate,
            Duration redisTimeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        if (redisTimeout == null || redisTimeout.isZero() || redisTimeout.isNegative()) {
            throw new IllegalArgumentException("Access-token Redis timeout must be positive");
        }
        this.redisTimeout = redisTimeout;
    }

    @Override
    public Mono<Jwt> decode(String token) {
        return delegate.decode(token).flatMap(this::validateSecurityState);
    }

    private Mono<Jwt> validateSecurityState(Jwt jwt) {
        AccessTokenClaims claims = accessTokenClaims(jwt);
        String hashTag = "{" + claims.userId() + "}";
        List<String> keys = List.of(
                KEY_PREFIX + hashTag + ":account",
                KEY_PREFIX + hashTag + ":family:" + claims.sessionFamilyId());

        return readState(keys)
                .map(values -> {
                    validateStateValues(values, claims.securityVersion());
                    return jwt;
                });
    }

    private Mono<List<String>> readState(List<String> keys) {
        return Mono.defer(() -> redisTemplate.opsForValue().multiGet(keys))
                .timeout(redisTimeout)
                .switchIfEmpty(Mono.error(stateUnavailable(null)))
                .onErrorMap(
                        error -> !(error instanceof AuthenticationServiceException),
                        this::stateUnavailable);
    }

    private AccessTokenClaims accessTokenClaims(Jwt jwt) {
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw invalidToken();
        }

        long userId;
        try {
            userId = Long.parseLong(subject);
        } catch (NumberFormatException error) {
            throw invalidToken();
        }
        if (userId <= 0) {
            throw invalidToken();
        }

        long securityVersion = positiveIntegralClaim(jwt.getClaim("securityVersion"));
        Object familyClaim = jwt.getClaim("sessionFamilyId");
        if (!(familyClaim instanceof String sessionFamilyId)
                || !StringUtils.hasText(sessionFamilyId)) {
            throw invalidToken();
        }

        return new AccessTokenClaims(
                Long.toString(userId),
                securityVersion,
                sessionFamilyId);
    }

    private long positiveIntegralClaim(Object value) {
        if (value == null) {
            throw invalidToken();
        }
        try {
            long parsed = switch (value) {
                case Byte number -> number.longValue();
                case Short number -> number.longValue();
                case Integer number -> number.longValue();
                case Long number -> number;
                case BigInteger number -> number.longValueExact();
                case BigDecimal number -> number.longValueExact();
                default -> throw invalidToken();
            };
            if (parsed <= 0) {
                throw invalidToken();
            }
            return parsed;
        } catch (ArithmeticException error) {
            throw invalidToken();
        }
    }

    private void validateStateValues(List<String> values, long tokenSecurityVersion) {
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

    private AccountState parseAccountState(String value) {
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
        } catch (NumberFormatException error) {
            throw stateUnavailable(error);
        }
    }

    private FamilyState parseFamilyState(String value) {
        if (!StringUtils.hasText(value)) {
            throw stateUnavailable(null);
        }
        return switch (value) {
            case "active" -> FamilyState.ACTIVE;
            case "revoked" -> FamilyState.REVOKED;
            default -> throw stateUnavailable(null);
        };
    }

    private BadJwtException invalidToken() {
        return new BadJwtException("Invalid access token");
    }

    private AuthenticationServiceException stateUnavailable(Throwable cause) {
        String message = "Access-token security state unavailable";
        return cause == null
                ? new AuthenticationServiceException(message)
                : new AuthenticationServiceException(message, cause);
    }

    private record AccessTokenClaims(
            String userId,
            long securityVersion,
            String sessionFamilyId) {
    }

    private record AccountState(boolean active, long securityVersion) {
    }

    private enum FamilyState {
        ACTIVE,
        REVOKED
    }
}
