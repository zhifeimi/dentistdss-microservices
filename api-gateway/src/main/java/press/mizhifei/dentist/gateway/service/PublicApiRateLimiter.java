package press.mizhifei.dentist.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fixed-window, per-client-IP request limit for the unauthenticated public
 * API ({@code /api/public/**}, the clinic directory the frontend build
 * prerenders from). The client address comes from
 * {@link TrustedProxyClientAddressResolver}, so forwarded headers count only
 * when the deployment trusts its proxy hops; Redis keys hold an HMAC of the
 * address, never the address itself. Fails closed (503) when Redis is down,
 * like the anonymous-session limiter.
 */
@Component
public class PublicApiRateLimiter {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** Domain separation from the anonymous-session fingerprints made with the same key. */
    private static final String FINGERPRINT_CONTEXT = "public-api\u001f";
    /**
     * Returns 0 while the window still has room, otherwise the milliseconds
     * until the window resets (at least 1).
     */
    private static final DefaultRedisScript<Long> WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 or redis.call('PTTL', KEYS[1]) < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if current <= tonumber(ARGV[2]) then
              return 0
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 1 then
              ttl = 1
            end
            return ttl
            """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final TrustedProxyClientAddressResolver clientAddressResolver;
    private final int sourceLimit;
    private final Duration window;
    private final String keyPrefix;
    private final SecretKeySpec fingerprintKey;

    public PublicApiRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            TrustedProxyClientAddressResolver clientAddressResolver,
            @Value("${app.security.public-api-rate-limit.source-limit:60}")
            int sourceLimit,
            @Value("${app.security.public-api-rate-limit.window:PT1M}")
            Duration window,
            @Value("${app.security.anonymous-session.namespace:local}")
            String namespace,
            @Value("${app.security.anonymous-session-issuance.fingerprint-key}")
            String fingerprintKey) {
        if (sourceLimit < 1) {
            throw new IllegalArgumentException("Public API rate limit must be positive");
        }
        if (window.isZero() || window.isNegative() || window.toMillis() < 1) {
            throw new IllegalArgumentException("Public API rate limit window must be positive");
        }
        if (!StringUtils.hasText(namespace) || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Public API rate limit namespace must contain only letters, numbers, dots, underscores, or hyphens");
        }
        if (!StringUtils.hasText(fingerprintKey)
                || fingerprintKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Public API rate limit fingerprint key must contain at least 32 bytes");
        }
        this.redisTemplate = redisTemplate;
        this.clientAddressResolver = clientAddressResolver;
        this.sourceLimit = sourceLimit;
        this.window = window;
        this.keyPrefix = "gateway:public-api:{" + namespace + "}:source:";
        this.fingerprintKey = new SecretKeySpec(fingerprintKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /** Counts this request against its client's window. */
    public Mono<Decision> tryAcquire(ServerHttpRequest request) {
        return redisTemplate.execute(
                        WINDOW_SCRIPT,
                        List.of(keyPrefix + sourceFingerprint(request)),
                        Long.toString(window.toMillis()),
                        Integer.toString(sourceLimit))
                .next()
                .switchIfEmpty(Mono.error(new DataAccessResourceFailureException(
                        "Redis rate-limit script returned no result")))
                .map(Decision::fromRetryAfterMillis)
                .onErrorMap(
                        DataAccessException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Public API rate limit unavailable",
                                error));
    }

    String sourceFingerprint(ServerHttpRequest request) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal(
                    (FINGERPRINT_CONTEXT + clientAddressResolver.resolve(request))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", ex);
        }
    }

    /**
     * @param allowed    whether the request fits in its client's window
     * @param retryAfter when blocked, how long until the window resets
     */
    public record Decision(boolean allowed, Duration retryAfter) {

        private static Decision fromRetryAfterMillis(long millis) {
            if (millis < 0) {
                throw new DataAccessResourceFailureException("Unexpected Redis rate-limit result: " + millis);
            }
            return millis == 0
                    ? new Decision(true, Duration.ZERO)
                    : new Decision(false, Duration.ofMillis(millis));
        }
    }
}
