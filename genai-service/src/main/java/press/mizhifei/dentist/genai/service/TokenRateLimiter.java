package press.mizhifei.dentist.genai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class TokenRateLimiter {

    private static final Pattern SUBJECT_KEY_PATTERN = Pattern.compile(
            "(?:user:[1-9][0-9]*|session:[A-Za-z0-9_-]{43})");

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT =
            new DefaultRedisScript<>("""
                    local now = redis.call('TIME')
                    local now_ms = (tonumber(now[1]) * 1000) + math.floor(tonumber(now[2]) / 1000)
                    local capacity = tonumber(ARGV[1])
                    local period_ms = tonumber(ARGV[2])
                    local requested = tonumber(ARGV[3])
                    local capacity_units = capacity * period_ms
                    local requested_units = requested * period_ms
                    local state = redis.call('HMGET', KEYS[1], 'balance', 'last_ms')
                    local balance = tonumber(state[1]) or capacity_units
                    local last_ms = tonumber(state[2]) or now_ms
                    local elapsed_ms = math.max(0, now_ms - last_ms)
                    balance = math.min(capacity_units, balance + (elapsed_ms * capacity))
                    local allowed = 0
                    if balance >= requested_units then
                      balance = balance - requested_units
                      allowed = 1
                    end
                    redis.call('HSET', KEYS[1], 'balance', balance, 'last_ms', now_ms)
                    redis.call('PEXPIRE', KEYS[1], period_ms)
                    return allowed
                    """, Long.class);

    private static final DefaultRedisScript<Long> ACQUIRE_STREAM_SCRIPT =
            new DefaultRedisScript<>("""
                    local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                    local maximum = tonumber(ARGV[1])
                    if current >= maximum then
                      return 0
                    end
                    current = current + 1
                    redis.call('SET', KEYS[1], current, 'PX', ARGV[2])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_STREAM_SCRIPT =
            new DefaultRedisScript<>("""
                    local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                    if current <= 1 then
                      redis.call('DEL', KEYS[1])
                      return 0
                    end
                    current = current - 1
                    redis.call('SET', KEYS[1], current, 'PX', ARGV[1])
                    return current
                    """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final long capacity;
    private final Duration refillPeriod;
    private final String tokenKeyPrefix;
    private final int maxConcurrentStreams;
    private final Duration streamLease;
    private final String streamKeyPrefix;

    public TokenRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${genai.rate-limit.capacity:10000}") long capacity,
            @Value("${genai.rate-limit.refill-period:PT3M}") Duration refillPeriod,
            @Value("${genai.rate-limit.token-key-prefix:genai:token-rate:v1:}")
            String tokenKeyPrefix,
            @Value("${genai.rate-limit.max-concurrent-streams:2}")
            int maxConcurrentStreams,
            @Value("${genai.rate-limit.stream-lease:PT10M}") Duration streamLease,
            @Value("${genai.rate-limit.stream-key-prefix:genai:concurrent-stream:v1:}")
            String streamKeyPrefix) {
        if (capacity <= 0 || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("GenAI token rate limit must be positive");
        }
        if (maxConcurrentStreams <= 0 || streamLease.isZero() || streamLease.isNegative()) {
            throw new IllegalArgumentException("GenAI stream limit must be positive");
        }
        if (!StringUtils.hasText(tokenKeyPrefix) || !StringUtils.hasText(streamKeyPrefix)) {
            throw new IllegalArgumentException("GenAI Redis key prefixes must be configured");
        }
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillPeriod = refillPeriod;
        this.tokenKeyPrefix = tokenKeyPrefix;
        this.maxConcurrentStreams = maxConcurrentStreams;
        this.streamLease = streamLease;
        this.streamKeyPrefix = streamKeyPrefix;
    }

    public Mono<Boolean> tryConsume(String subjectKey, long tokens) {
        if (!isValidSubjectKey(subjectKey)) {
            return Mono.error(new IllegalArgumentException("Invalid GenAI quota subject"));
        }
        if (tokens <= 0) {
            return Mono.error(new IllegalArgumentException("Token cost must be positive"));
        }
        if (tokens > capacity) {
            return Mono.just(false);
        }

        return execute(
                        TOKEN_BUCKET_SCRIPT,
                        tokenKeyPrefix + subjectKey,
                        Long.toString(capacity),
                        Long.toString(refillPeriod.toMillis()),
                        Long.toString(tokens))
                .map(result -> result == 1L);
    }

    public Mono<Boolean> tryAcquireStream(String subjectKey) {
        if (!isValidSubjectKey(subjectKey)) {
            return Mono.error(new IllegalArgumentException("Invalid GenAI stream subject"));
        }

        return execute(
                        ACQUIRE_STREAM_SCRIPT,
                        streamKeyPrefix + subjectKey,
                        Integer.toString(maxConcurrentStreams),
                        Long.toString(streamLease.toMillis()))
                .map(result -> result == 1L);
    }

    public Mono<Void> releaseStream(String subjectKey) {
        if (!isValidSubjectKey(subjectKey)) {
            return Mono.error(new IllegalArgumentException("Invalid GenAI stream subject"));
        }

        return execute(
                        RELEASE_STREAM_SCRIPT,
                        streamKeyPrefix + subjectKey,
                        Long.toString(streamLease.toMillis()))
                .then();
    }

    private Mono<Long> execute(
            DefaultRedisScript<Long> script,
            String key,
            String... arguments) {
        return redisTemplate.execute(script, List.of(key), (Object[]) arguments)
                .next()
                .switchIfEmpty(Mono.error(new DataAccessResourceFailureException(
                        "Redis script returned no result")))
                .onErrorMap(
                        DataAccessException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "GenAI quota service unavailable",
                                error));
    }

    private boolean isValidSubjectKey(String subjectKey) {
        return StringUtils.hasText(subjectKey)
                && SUBJECT_KEY_PATTERN.matcher(subjectKey).matches();
    }
}
