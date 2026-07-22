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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class AnonymousSessionIssuanceLimiter {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final DefaultRedisScript<Long> ISSUANCE_SCRIPT =
            new DefaultRedisScript<>("""
                    local source_current = tonumber(redis.call('GET', KEYS[2]) or '0')
                    local global_current = tonumber(redis.call('GET', KEYS[3]) or '0')
                    local source_limit = tonumber(ARGV[3])
                    local global_limit = tonumber(ARGV[4])
                    if redis.call('EXISTS', KEYS[1]) == 1 then
                      return 2
                    end
                    if source_current >= source_limit or global_current >= global_limit then
                      return 0
                    end
                    local created = redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2], 'NX')
                    if not created then
                      return 2
                    end
                    source_current = redis.call('INCR', KEYS[2])
                    if source_current == 1 or redis.call('PTTL', KEYS[2]) < 0 then
                      redis.call('PEXPIRE', KEYS[2], ARGV[5])
                    end
                    global_current = redis.call('INCR', KEYS[3])
                    if global_current == 1 or redis.call('PTTL', KEYS[3]) < 0 then
                      redis.call('PEXPIRE', KEYS[3], ARGV[5])
                    end
                    return 1
                    """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final TrustedProxyClientAddressResolver clientAddressResolver;
    private final int sourceLimit;
    private final int globalLimit;
    private final Duration window;
    private final String keyPrefix;
    private final SecretKeySpec fingerprintKey;

    public AnonymousSessionIssuanceLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            TrustedProxyClientAddressResolver clientAddressResolver,
            @Value("${app.security.anonymous-session-issuance.source-limit:20}")
            int sourceLimit,
            @Value("${app.security.anonymous-session-issuance.global-limit:500}")
            int globalLimit,
            @Value("${app.security.anonymous-session-issuance.window:PT10M}")
            Duration window,
            @Value("${app.security.anonymous-session.namespace:local}")
            String namespace,
            @Value("${app.security.anonymous-session-issuance.fingerprint-key}")
            String fingerprintKey) {
        if (sourceLimit < 1 || globalLimit < 1) {
            throw new IllegalArgumentException(
                    "Anonymous session issuance limits must be positive");
        }
        if (window.isZero() || window.isNegative() || window.toMillis() < 1) {
            throw new IllegalArgumentException(
                    "Anonymous session issuance window must be positive");
        }
        if (!StringUtils.hasText(namespace)
                || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Anonymous session namespace must contain only letters, numbers, dots, underscores, or hyphens");
        }
        if (!StringUtils.hasText(fingerprintKey)
                || fingerprintKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "Anonymous session fingerprint key must contain at least 32 bytes");
        }
        this.redisTemplate = redisTemplate;
        this.clientAddressResolver = clientAddressResolver;
        this.sourceLimit = sourceLimit;
        this.globalLimit = globalLimit;
        this.window = window;
        this.keyPrefix = "gateway:anonymous-session:{" + namespace + "}:";
        this.fingerprintKey = new SecretKeySpec(
                fingerprintKey.getBytes(StandardCharsets.UTF_8),
                HMAC_ALGORITHM);
    }

    public Mono<IssuanceResult> tryIssue(
            ServerHttpRequest request,
            String sessionId,
            Duration sessionTtl) {
        if (!StringUtils.hasText(sessionId)
                || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return Mono.error(new IllegalArgumentException("Invalid anonymous session identifier"));
        }
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()
                || sessionTtl.toMillis() < 1) {
            return Mono.error(new IllegalArgumentException("Anonymous session TTL must be positive"));
        }

        String sourceFingerprint = sourceFingerprint(request);
        return redisTemplate.execute(
                        ISSUANCE_SCRIPT,
                        List.of(
                                keyPrefix + "marker:" + sessionId,
                                keyPrefix + "issuance:source:" + sourceFingerprint,
                                keyPrefix + "issuance:global"),
                        sourceFingerprint,
                        Long.toString(sessionTtl.toMillis()),
                        Integer.toString(sourceLimit),
                        Integer.toString(globalLimit),
                        Long.toString(window.toMillis()))
                .next()
                .switchIfEmpty(Mono.error(new DataAccessResourceFailureException(
                        "Redis issuance script returned no result")))
                .map(IssuanceResult::fromCode)
                .onErrorMap(
                        DataAccessException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Anonymous session service unavailable",
                                error));
    }

    boolean isBoundToRequest(ServerHttpRequest request, String markerValue) {
        if (!StringUtils.hasText(markerValue)) {
            return false;
        }
        return MessageDigest.isEqual(
                sourceFingerprint(request).getBytes(StandardCharsets.UTF_8),
                markerValue.getBytes(StandardCharsets.UTF_8));
    }

    String sourceFingerprint(ServerHttpRequest request) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal(
                    clientAddressResolver.resolve(request).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", ex);
        }
    }

    public enum IssuanceResult {
        LIMIT_EXCEEDED,
        ISSUED,
        COLLISION;

        private static IssuanceResult fromCode(long code) {
            return switch ((int) code) {
                case 0 -> LIMIT_EXCEEDED;
                case 1 -> ISSUED;
                case 2 -> COLLISION;
                default -> throw new DataAccessResourceFailureException(
                        "Unexpected Redis issuance result: " + code);
            };
        }
    }
}
