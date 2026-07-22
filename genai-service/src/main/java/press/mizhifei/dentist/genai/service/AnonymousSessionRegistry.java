package press.mizhifei.dentist.genai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates anonymous session identifiers against the gateway's Redis registry.
 */
@Service
public class AnonymousSessionRegistry {

    private static final String INVALID_PROOF = "invalid";
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern SOURCE_FINGERPRINT_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final DefaultRedisScript<String> CONSUME_PROOF_SCRIPT =
            new DefaultRedisScript<>("""
                    local marker = redis.call('GET', KEYS[1])
                    local proof_session = redis.call('GET', KEYS[2])
                    if proof_session then
                      redis.call('DEL', KEYS[2])
                    end
                    if not marker or not proof_session or proof_session ~= ARGV[1] then
                      return 'invalid'
                    end
                    return marker
                    """, String.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public AnonymousSessionRegistry(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${app.security.anonymous-session.namespace:local}")
            String namespace) {
        if (!StringUtils.hasText(namespace)
                || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Anonymous session namespace must contain only letters, numbers, dots, underscores, or hyphens");
        }
        this.redisTemplate = redisTemplate;
        this.keyPrefix = "gateway:anonymous-session:{" + namespace + "}:";
    }

    public Mono<VerifiedAnonymousSession> requireGatewayIssuedSession(
            String sessionId,
            String oneTimeProof) {
        if (!isValidToken(sessionId) || !isValidToken(oneTimeProof)) {
            return Mono.error(invalidSession());
        }

        return redisTemplate.execute(
                        CONSUME_PROOF_SCRIPT,
                        List.of(
                                keyPrefix + "marker:" + sessionId,
                                keyPrefix + "proof:" + oneTimeProof),
                        sessionId)
                .next()
                .switchIfEmpty(Mono.error(new DataAccessResourceFailureException(
                        "Redis anonymous proof script returned no result")))
                .flatMap(sourceFingerprint -> {
                    if (INVALID_PROOF.equals(sourceFingerprint)) {
                        return Mono.error(invalidSession());
                    }
                    if (!SOURCE_FINGERPRINT_PATTERN.matcher(sourceFingerprint).matches()) {
                        return Mono.error(new DataAccessResourceFailureException(
                                "Unexpected Redis anonymous session marker"));
                    }
                    return Mono.just(new VerifiedAnonymousSession(
                            sessionId,
                            sourceFingerprint));
                })
                .onErrorMap(
                        DataAccessException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Anonymous session validation unavailable",
                                error));
    }

    private boolean isValidToken(String token) {
        return StringUtils.hasText(token) && TOKEN_PATTERN.matcher(token).matches();
    }

    private ResponseStatusException invalidSession() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Gateway-issued anonymous session required");
    }

    public record VerifiedAnonymousSession(
            String sessionId,
            String sourceFingerprint) {
    }
}
