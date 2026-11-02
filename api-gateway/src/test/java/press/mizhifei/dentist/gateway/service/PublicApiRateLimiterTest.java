package press.mizhifei.dentist.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicApiRateLimiterTest {

    private static final String FINGERPRINT_KEY = "test-fingerprint-key-with-at-least-32-bytes";

    private ReactiveStringRedisTemplate redisTemplate;
    private PublicApiRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        limiter = limiter(new TrustedProxyClientAddressResolver(0));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void countsTheClientUnderAnHmacKeyWithTheWindowAndLimit() {
        whenExecuteReturns(0L);

        StepVerifier.create(limiter.tryAcquire(request("203.0.113.10")))
                .assertNext(decision -> assertTrue(decision.allowed()))
                .verifyComplete();

        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keys.capture(), args.capture());
        String key = (String) keys.getValue().get(0);
        assertTrue(key.startsWith("gateway:public-api:{test}:source:"), key);
        // Redis never holds the raw client address.
        assertFalse(key.contains("203.0.113.10"), key);
        assertEquals(List.of("60000", "60"), List.of(args.getValue()));
    }

    @Test
    void fullWindowBlocksWithTheTimeUntilReset() {
        whenExecuteReturns(42_500L);

        StepVerifier.create(limiter.tryAcquire(request("203.0.113.10")))
                .assertNext(decision -> {
                    assertFalse(decision.allowed());
                    assertEquals(Duration.ofMillis(42_500), decision.retryAfter());
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void redisFailureFailsClosed() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(limiter.tryAcquire(request("203.0.113.10")))
                .expectErrorSatisfies(error -> {
                    ResponseStatusException status = (ResponseStatusException) error;
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, status.getStatusCode());
                })
                .verify();
    }

    @Test
    void forwardedForCountsOnlyBehindTrustedProxies() {
        ServerHttpRequest viaProxy = MockServerHttpRequest.get("/api/public/clinics")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 443))
                .header("X-Forwarded-For", "198.51.100.7")
                .build();
        ServerHttpRequest direct = request("10.0.0.5");

        PublicApiRateLimiter untrusting = limiter(new TrustedProxyClientAddressResolver(0));
        assertEquals(untrusting.sourceFingerprint(direct), untrusting.sourceFingerprint(viaProxy),
                "without trusted proxies a forged X-Forwarded-For must not create a new bucket");

        PublicApiRateLimiter trusting = limiter(new TrustedProxyClientAddressResolver(1));
        assertNotEquals(trusting.sourceFingerprint(direct), trusting.sourceFingerprint(viaProxy));
    }

    @Test
    void rejectsUnsafeConfiguration() {
        TrustedProxyClientAddressResolver resolver = new TrustedProxyClientAddressResolver(0);
        assertThrows(IllegalArgumentException.class, () -> new PublicApiRateLimiter(
                redisTemplate, resolver, 0, Duration.ofMinutes(1), "test", FINGERPRINT_KEY));
        assertThrows(IllegalArgumentException.class, () -> new PublicApiRateLimiter(
                redisTemplate, resolver, 60, Duration.ZERO, "test", FINGERPRINT_KEY));
        assertThrows(IllegalArgumentException.class, () -> new PublicApiRateLimiter(
                redisTemplate, resolver, 60, Duration.ofMinutes(1), "bad namespace", FINGERPRINT_KEY));
        assertThrows(IllegalArgumentException.class, () -> new PublicApiRateLimiter(
                redisTemplate, resolver, 60, Duration.ofMinutes(1), "test", "short"));
    }

    private PublicApiRateLimiter limiter(TrustedProxyClientAddressResolver resolver) {
        return new PublicApiRateLimiter(redisTemplate, resolver, 60, Duration.ofMinutes(1), "test", FINGERPRINT_KEY);
    }

    private static ServerHttpRequest request(String address) {
        return MockServerHttpRequest.get("/api/public/clinics")
                .remoteAddress(new InetSocketAddress(address, 443))
                .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void whenExecuteReturns(long value) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.just(value));
    }
}
