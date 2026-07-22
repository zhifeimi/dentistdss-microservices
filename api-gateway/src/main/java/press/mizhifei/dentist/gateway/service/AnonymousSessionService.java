package press.mizhifei.dentist.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Manages server-issued anonymous session identifiers in Redis.
 */
@Service
public class AnonymousSessionService {

    private static final int SESSION_ID_BYTES = 32;
    private static final int MAX_CREATE_ATTEMPTS = 3;
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final ReactiveStringRedisTemplate redisTemplate;
    private final AnonymousSessionIssuanceLimiter issuanceLimiter;
    private final Duration sessionTtl;
    private final String keyPrefix;
    private final SecureRandom secureRandom = new SecureRandom();

    public AnonymousSessionService(
            ReactiveStringRedisTemplate redisTemplate,
            AnonymousSessionIssuanceLimiter issuanceLimiter,
            @Value("${app.security.anonymous-session.ttl:PT24H}")
            Duration sessionTtl,
            @Value("${app.security.anonymous-session.namespace:local}")
            String namespace) {
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()
                || sessionTtl.toMillis() < 1) {
            throw new IllegalArgumentException("Anonymous session TTL must be positive");
        }
        if (!StringUtils.hasText(namespace)
                || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Anonymous session namespace must contain only letters, numbers, dots, underscores, or hyphens");
        }
        this.redisTemplate = redisTemplate;
        this.issuanceLimiter = issuanceLimiter;
        this.sessionTtl = sessionTtl;
        this.keyPrefix = "gateway:anonymous-session:{" + namespace + "}:";
    }

    /**
     * Reuses a valid server-issued session without extending its absolute lifetime,
     * or atomically rate-limits and creates a replacement for the exact anonymous
     * GenAI help route. Unknown client values are never adopted.
     */
    public Mono<String> getOrCreateAnonymousSession(
            String existingSessionId,
            ServerHttpRequest request) {
        return reuseSession(existingSessionId, request)
                .switchIfEmpty(Mono.defer(() -> createSession(request, MAX_CREATE_ATTEMPTS)))
                .onErrorMap(
                        DataAccessException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Anonymous session service unavailable",
                                error));
    }

    private Mono<String> reuseSession(
            String existingSessionId,
            ServerHttpRequest request) {
        if (!isValidSessionId(existingSessionId)) {
            return Mono.empty();
        }

        return Mono.defer(() -> redisTemplate.opsForValue().get(
                        sessionKey(existingSessionId)))
                .filter(marker -> issuanceLimiter.isBoundToRequest(request, marker))
                .map(marker -> existingSessionId);
    }

    private Mono<String> createSession(
            ServerHttpRequest request,
            int attemptsRemaining) {
        if (attemptsRemaining == 0) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to allocate anonymous session"));
        }

        return Mono.defer(() -> {
            String sessionId = generateSecureSessionId();
            return issuanceLimiter.tryIssue(request, sessionId, sessionTtl)
                    .flatMap(result -> switch (result) {
                        case ISSUED -> Mono.just(sessionId);
                        case LIMIT_EXCEEDED -> Mono.error(new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Anonymous session issuance limit exceeded"));
                        case COLLISION -> createSession(request, attemptsRemaining - 1);
                    });
        });
    }

    private boolean isValidSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) && SESSION_ID_PATTERN.matcher(sessionId).matches();
    }

    private String generateSecureSessionId() {
        byte[] randomBytes = new byte[SESSION_ID_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String sessionKey(String sessionId) {
        return keyPrefix + "marker:" + sessionId;
    }
}
