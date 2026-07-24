package press.mizhifei.dentist.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.List;
import java.util.Objects;

/**
 * Decorates a local JWT decoder with fail-closed current-account and
 * session-family validation from Redis.
 *
 * <p>Account values use {@code <securityVersion>:1} for active or
 * {@code <securityVersion>:0} for inactive. Family values use {@code active}
 * or {@code revoked}.</p>
 */
public final class RedisAccessTokenJwtDecoder implements JwtDecoder {

    private final JwtDecoder delegate;
    private final StringRedisTemplate redisTemplate;

    public RedisAccessTokenJwtDecoder(
            JwtDecoder delegate,
            StringRedisTemplate redisTemplate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    }

    @Override
    public Jwt decode(String token) {
        Jwt jwt = delegate.decode(token);
        AccessTokenStateSupport.AccessTokenClaims claims = AccessTokenStateSupport.claims(jwt);
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(AccessTokenStateSupport.keys(claims));
        } catch (RuntimeException ex) {
            throw AccessTokenStateSupport.stateUnavailable(ex);
        }
        AccessTokenStateSupport.validate(values, claims.securityVersion());
        return jwt;
    }
}
