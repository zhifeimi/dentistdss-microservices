package press.mizhifei.dentist.security;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Non-blocking reactive counterpart to {@link RedisAccessTokenJwtDecoder}.
 */
public final class RedisAccessTokenReactiveJwtDecoder implements ReactiveJwtDecoder {

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
        AccessTokenStateSupport.AccessTokenClaims claims = AccessTokenStateSupport.claims(jwt);
        List<String> keys = AccessTokenStateSupport.keys(claims);
        return Mono.defer(() -> redisTemplate.opsForValue().multiGet(keys))
                .timeout(redisTimeout)
                .switchIfEmpty(Mono.error(AccessTokenStateSupport.stateUnavailable(null)))
                .onErrorMap(
                        error -> !(error instanceof AuthenticationServiceException),
                        AccessTokenStateSupport::stateUnavailable)
                .map(values -> {
                    AccessTokenStateSupport.validate(values, claims.securityVersion());
                    return jwt;
                });
    }
}
