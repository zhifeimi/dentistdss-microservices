package press.mizhifei.dentist.genai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenRateLimiterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private TokenRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        rateLimiter = new TokenRateLimiter(
                redisTemplate,
                10,
                Duration.ofMinutes(3),
                "genai:token-rate:v1:",
                2,
                Duration.ofMinutes(10),
                "genai:concurrent-stream:v1:");
    }

    @Test
    void mapsAtomicTokenBucketDecisionAndUsesScopedKey() {
        whenExecuteReturns(1L);

        StepVerifier.create(rateLimiter.tryConsume("user:42", 3))
                .expectNext(true)
                .verifyComplete();

        var arguments = forClass(Object[].class);
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("genai:token-rate:v1:user:42")),
                arguments.capture());
        assertArrayEquals(new Object[]{"10", "180000", "3"},
                arguments.getValue());
    }

    @Test
    void mapsRedisDenialWithoutFailingOpen() {
        whenExecuteReturns(0L);

        StepVerifier.create(rateLimiter.tryConsume(
                        "session:abcdefghijklmnopqrstuvwxyzABCDEFGH012345678", 10))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void rejectsInvalidAndOversizedCostsBeforeRedis() {
        StepVerifier.create(rateLimiter.tryConsume("user:42", 0))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(rateLimiter.tryConsume("email:patient@example.com", 1))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(rateLimiter.tryConsume("user:42", 11))
                .expectNext(false)
                .verifyComplete();

        verify(redisTemplate, never()).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void acquiresReplicaSharedStreamLeaseWithConfiguredLimitAndTtl() {
        whenExecuteReturns(1L);

        StepVerifier.create(rateLimiter.tryAcquireStream("user:42"))
                .expectNext(true)
                .verifyComplete();

        var arguments = forClass(Object[].class);
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("genai:concurrent-stream:v1:user:42")),
                arguments.capture());
        assertArrayEquals(new Object[]{"2", "600000"}, arguments.getValue());
    }

    @Test
    void deniesStreamWhenReplicaSharedLimitIsExhausted() {
        whenExecuteReturns(0L);

        StepVerifier.create(rateLimiter.tryAcquireStream("user:42"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void releasesReplicaSharedStreamLeaseWithConfiguredTtl() {
        whenExecuteReturns(1L);

        StepVerifier.create(rateLimiter.releaseStream("user:42"))
                .verifyComplete();

        var arguments = forClass(Object[].class);
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("genai:concurrent-stream:v1:user:42")),
                arguments.capture());
        assertArrayEquals(new Object[]{"600000"}, arguments.getValue());
    }

    @Test
    void mapsRedisFailureToServiceUnavailable() {
        whenExecuteFails(new DataAccessResourceFailureException("redis unavailable"));

        assertServiceUnavailable(rateLimiter.tryConsume("user:42", 1));
        assertServiceUnavailable(rateLimiter.tryAcquireStream("user:42"));
        assertServiceUnavailable(rateLimiter.releaseStream("user:42"));
    }

    @Test
    void mapsMissingScriptResultToServiceUnavailable() {
        whenExecuteIsEmpty();

        assertServiceUnavailable(rateLimiter.tryConsume("user:42", 1));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void whenExecuteReturns(long value) {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.just(value));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void whenExecuteFails(Throwable error) {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.error(error));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void whenExecuteIsEmpty() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.empty());
    }

    private void assertServiceUnavailable(Mono<?> operation) {
        StepVerifier.create(operation)
                .expectErrorSatisfies(error -> {
                    ResponseStatusException statusException =
                            (ResponseStatusException) error;
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                            statusException.getStatusCode());
                })
                .verify();
    }
}
