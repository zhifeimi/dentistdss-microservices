package press.mizhifei.dentist.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnonymousSessionIssuanceLimiterTest {

    private static final String NAMESPACE = "test";
    private static final String FINGERPRINT_KEY =
            "test-fingerprint-key-with-at-least-32-bytes";
    private static final String KEY_PREFIX = "gateway:anonymous-session:{test}:";
    private static final String SESSION_ID = "S".repeat(43);
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final Duration WINDOW = Duration.ofMinutes(2);

    private ReactiveStringRedisTemplate redisTemplate;
    private AnonymousSessionIssuanceLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        limiter = limiter(new TrustedProxyClientAddressResolver(0));
    }

    @Test
    void atomicallyUsesMarkerSourceAndGlobalKeysInOneClusterSlot() {
        whenExecuteReturns(1L);
        ServerHttpRequest request = request(
                "203.0.113.7",
                "DentistDSS-Test/1.0");

        StepVerifier.create(limiter.tryIssue(request, SESSION_ID, SESSION_TTL))
                .expectNext(AnonymousSessionIssuanceLimiter.IssuanceResult.ISSUED)
                .verifyComplete();

        var arguments = forClass(Object[].class);
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of(
                        KEY_PREFIX + "marker:" + SESSION_ID,
                        KEY_PREFIX + "issuance:source:" + hmac("203.0.113.7"),
                        KEY_PREFIX + "issuance:global")),
                arguments.capture());
        assertArrayEquals(new Object[]{
                        hmac("203.0.113.7"),
                        Long.toString(SESSION_TTL.toMillis()),
                        "7",
                        "70",
                        "120000"},
                arguments.getValue());
    }

    @Test
    void mapsLimitAndCollisionResults() {
        whenExecuteReturns(0L);
        StepVerifier.create(limiter.tryIssue(
                        request("203.0.113.8", "test"),
                        SESSION_ID,
                        SESSION_TTL))
                .expectNext(AnonymousSessionIssuanceLimiter.IssuanceResult.LIMIT_EXCEEDED)
                .verifyComplete();

        clearInvocations(redisTemplate);
        whenExecuteReturns(2L);
        StepVerifier.create(limiter.tryIssue(
                        request("203.0.113.8", "test"),
                        SESSION_ID,
                        SESSION_TTL))
                .expectNext(AnonymousSessionIssuanceLimiter.IssuanceResult.COLLISION)
                .verifyComplete();
    }

    @Test
    void changingCallerControlledUserAgentDoesNotChangeSourceBucket() {
        String firstKey = executeAndCaptureSourceKey(request(
                "203.0.113.9",
                "first-user-agent"));
        clearInvocations(redisTemplate);
        String secondKey = executeAndCaptureSourceKey(request(
                "203.0.113.9",
                "rotated-user-agent"));

        assertEquals(firstKey, secondKey);
    }

    @Test
    void bindsMarkerToResolvedClientSource() {
        ServerHttpRequest original = request("203.0.113.9", "first-user-agent");
        ServerHttpRequest differentSource = request("203.0.113.10", "first-user-agent");
        String marker = hmac("203.0.113.9");

        assertTrue(limiter.isBoundToRequest(original, marker));
        assertFalse(limiter.isBoundToRequest(differentSource, marker));
        assertFalse(limiter.isBoundToRequest(original, null));
    }

    @Test
    void ignoresCallerSuppliedForwardedAddressByDefault() {
        ServerHttpRequest first = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("203.0.113.10", 443))
                .header("X-Forwarded-For", "198.51.100.1")
                .build();
        ServerHttpRequest second = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("203.0.113.10", 443))
                .header("X-Forwarded-For", "198.51.100.200")
                .build();

        String firstKey = executeAndCaptureSourceKey(first);
        clearInvocations(redisTemplate);
        String secondKey = executeAndCaptureSourceKey(second);

        assertEquals(firstKey, secondKey);
    }

    @Test
    void usesStableFallbackWhenRemoteAddressIsUnavailable() {
        whenExecuteReturns(1L);
        ServerHttpRequest request = MockServerHttpRequest.get("/").build();

        StepVerifier.create(limiter.tryIssue(request, SESSION_ID, SESSION_TTL))
                .expectNext(AnonymousSessionIssuanceLimiter.IssuanceResult.ISSUED)
                .verifyComplete();

        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of(
                        KEY_PREFIX + "marker:" + SESSION_ID,
                        KEY_PREFIX + "issuance:source:" + hmac("unknown"),
                        KEY_PREFIX + "issuance:global")),
                any(Object[].class));
    }

    @Test
    void rejectsInvalidConfigurationAndRequestInputs() {
        TrustedProxyClientAddressResolver resolver = new TrustedProxyClientAddressResolver(0);
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionIssuanceLimiter(
                        redisTemplate, resolver, 0, 1, WINDOW, NAMESPACE, FINGERPRINT_KEY));
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionIssuanceLimiter(
                        redisTemplate, resolver, 1, 0, WINDOW, NAMESPACE, FINGERPRINT_KEY));
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionIssuanceLimiter(
                        redisTemplate, resolver, 1, 1, Duration.ZERO, NAMESPACE, FINGERPRINT_KEY));
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionIssuanceLimiter(
                        redisTemplate, resolver, 1, 1, Duration.ofNanos(1), NAMESPACE, FINGERPRINT_KEY));
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionIssuanceLimiter(
                        redisTemplate, resolver, 1, 1, WINDOW, "bad{namespace}", FINGERPRINT_KEY));
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionIssuanceLimiter(
                        redisTemplate, resolver, 1, 1, WINDOW, NAMESPACE, "short"));

        StepVerifier.create(limiter.tryIssue(
                        request("203.0.113.10", "test"),
                        "malformed",
                        SESSION_TTL))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(limiter.tryIssue(
                        request("203.0.113.10", "test"),
                        SESSION_ID,
                        Duration.ZERO))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(redisTemplate, never()).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class));
    }

    @Test
    void mapsRedisFailureToServiceUnavailable() {
        whenExecuteFails(new DataAccessResourceFailureException(
                "redis unavailable"));

        StepVerifier.create(limiter.tryIssue(
                        request("203.0.113.11", "test"),
                        SESSION_ID,
                        SESSION_TTL))
                .expectErrorSatisfies(this::assertServiceUnavailable)
                .verify();
    }

    @Test
    void mapsMissingOrUnexpectedScriptResultToServiceUnavailable() {
        whenExecuteIsEmpty();
        StepVerifier.create(limiter.tryIssue(
                        request("203.0.113.12", "test"),
                        SESSION_ID,
                        SESSION_TTL))
                .expectErrorSatisfies(this::assertServiceUnavailable)
                .verify();

        clearInvocations(redisTemplate);
        whenExecuteReturns(99L);
        StepVerifier.create(limiter.tryIssue(
                        request("203.0.113.12", "test"),
                        SESSION_ID,
                        SESSION_TTL))
                .expectErrorSatisfies(this::assertServiceUnavailable)
                .verify();
    }

    private AnonymousSessionIssuanceLimiter limiter(
            TrustedProxyClientAddressResolver resolver) {
        return new AnonymousSessionIssuanceLimiter(
                redisTemplate,
                resolver,
                7,
                70,
                WINDOW,
                NAMESPACE,
                FINGERPRINT_KEY);
    }

    private ServerHttpRequest request(String address, String userAgent) {
        return MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress(address, 443))
                .header(HttpHeaders.USER_AGENT, userAgent)
                .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String executeAndCaptureSourceKey(ServerHttpRequest request) {
        whenExecuteReturns(1L);
        StepVerifier.create(limiter.tryIssue(request, SESSION_ID, SESSION_TTL))
                .expectNext(AnonymousSessionIssuanceLimiter.IssuanceResult.ISSUED)
                .verifyComplete();

        var keys = forClass(List.class);
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                keys.capture(),
                any(Object[].class));
        return String.valueOf(keys.getValue().get(1));
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

    private void assertServiceUnavailable(Throwable error) {
        ResponseStatusException statusException = (ResponseStatusException) error;
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                statusException.getStatusCode());
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    FINGERPRINT_KEY.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
