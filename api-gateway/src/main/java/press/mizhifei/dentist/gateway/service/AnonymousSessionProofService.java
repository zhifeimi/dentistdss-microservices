package press.mizhifei.dentist.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Mints short-lived one-time proofs for gateway-authorized anonymous requests.
 */
@Service
public class AnonymousSessionProofService {

    private static final int PROOF_BYTES = 32;
    private static final int MAX_CREATE_ATTEMPTS = 3;
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final DefaultRedisScript<Long> ISSUE_PROOF_SCRIPT =
            new DefaultRedisScript<>("""
                    local marker = redis.call('GET', KEYS[1])
                    if not marker or marker ~= ARGV[1] then
                      return 0
                    end
                    local source_current = tonumber(redis.call('GET', KEYS[3]) or '0')
                    local global_current = tonumber(redis.call('GET', KEYS[4]) or '0')
                    local source_limit = tonumber(ARGV[4])
                    local global_limit = tonumber(ARGV[5])
                    if source_current >= source_limit or global_current >= global_limit then
                      return 3
                    end
                    local created = redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3], 'NX')
                    if not created then
                      return 2
                    end
                    source_current = redis.call('INCR', KEYS[3])
                    if source_current == 1 or redis.call('PTTL', KEYS[3]) < 0 then
                      redis.call('PEXPIRE', KEYS[3], ARGV[6])
                    end
                    global_current = redis.call('INCR', KEYS[4])
                    if global_current == 1 or redis.call('PTTL', KEYS[4]) < 0 then
                      redis.call('PEXPIRE', KEYS[4], ARGV[6])
                    end
                    return 1
                    """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final AnonymousSessionIssuanceLimiter issuanceLimiter;
    private final Duration proofTtl;
    private final int sourceLimit;
    private final int globalLimit;
    private final Duration window;
    private final String keyPrefix;
    private final SecureRandom secureRandom = new SecureRandom();

    public AnonymousSessionProofService(
            ReactiveStringRedisTemplate redisTemplate,
            AnonymousSessionIssuanceLimiter issuanceLimiter,
            @Value("${app.security.anonymous-session.proof-ttl:PT30S}")
            Duration proofTtl,
            @Value("${app.security.anonymous-session-proof-issuance.source-limit:60}")
            int sourceLimit,
            @Value("${app.security.anonymous-session-proof-issuance.global-limit:5000}")
            int globalLimit,
            @Value("${app.security.anonymous-session-proof-issuance.window:PT1M}")
            Duration window,
            @Value("${app.security.anonymous-session.namespace:local}")
            String namespace) {
        if (proofTtl == null || proofTtl.isZero() || proofTtl.isNegative()
                || proofTtl.toMillis() < 1) {
            throw new IllegalArgumentException("Anonymous session proof TTL must be positive");
        }
        if (sourceLimit < 1 || globalLimit < 1) {
            throw new IllegalArgumentException(
                    "Anonymous session proof issuance limits must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()
                || window.toMillis() < 1) {
            throw new IllegalArgumentException(
                    "Anonymous session proof issuance window must be positive");
        }
        if (!StringUtils.hasText(namespace)
                || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Anonymous session namespace must contain only letters, numbers, dots, underscores, or hyphens");
        }
        this.redisTemplate = redisTemplate;
        this.issuanceLimiter = issuanceLimiter;
        this.proofTtl = proofTtl;
        this.sourceLimit = sourceLimit;
        this.globalLimit = globalLimit;
        this.window = window;
        this.keyPrefix = "gateway:anonymous-session:{" + namespace + "}:";
    }

    public Mono<String> issueProof(ServerHttpRequest request, String sessionId) {
        if (!StringUtils.hasText(sessionId)
                || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return Mono.error(new IllegalArgumentException("Invalid anonymous session identifier"));
        }
        return createProof(request, sessionId, MAX_CREATE_ATTEMPTS)
                .onErrorMap(
                        DataAccessException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Anonymous session proof service unavailable",
                                error));
    }

    private Mono<String> createProof(
            ServerHttpRequest request,
            String sessionId,
            int attemptsRemaining) {
        if (attemptsRemaining == 0) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to allocate anonymous session proof"));
        }

        return Mono.defer(() -> {
            String proof = generateProof();
            String sourceFingerprint = issuanceLimiter.sourceFingerprint(request);
            return redisTemplate.execute(
                            ISSUE_PROOF_SCRIPT,
                            List.of(
                                    keyPrefix + "marker:" + sessionId,
                                    keyPrefix + "proof:" + proof,
                                    keyPrefix + "proof-issuance:source:" + sourceFingerprint,
                                    keyPrefix + "proof-issuance:global"),
                            sourceFingerprint,
                            sessionId,
                            Long.toString(proofTtl.toMillis()),
                            Integer.toString(sourceLimit),
                            Integer.toString(globalLimit),
                            Long.toString(window.toMillis()))
                    .next()
                    .switchIfEmpty(Mono.error(new DataAccessResourceFailureException(
                            "Redis proof script returned no result")))
                    .flatMap(result -> switch (result.intValue()) {
                        case 0 -> Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "Anonymous session is not bound to this request"));
                        case 1 -> Mono.just(proof);
                        case 2 -> createProof(request, sessionId, attemptsRemaining - 1);
                        case 3 -> Mono.error(new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Anonymous session proof issuance limit exceeded"));
                        default -> Mono.error(new DataAccessResourceFailureException(
                                "Unexpected Redis proof result: " + result));
                    });
        });
    }

    private String generateProof() {
        byte[] randomBytes = new byte[PROOF_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
