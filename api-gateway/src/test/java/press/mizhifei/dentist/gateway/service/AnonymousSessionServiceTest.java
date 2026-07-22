package press.mizhifei.dentist.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnonymousSessionServiceTest {

    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final String NAMESPACE = "test";
    private static final String KEY_PREFIX = "gateway:anonymous-session:{test}:marker:";

    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valueOperations;
    private AnonymousSessionIssuanceLimiter issuanceLimiter;
    private AnonymousSessionService anonymousSessionService;
    private ServerHttpRequest request;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        valueOperations = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        issuanceLimiter = mock(AnonymousSessionIssuanceLimiter.class);
        anonymousSessionService = new AnonymousSessionService(
                redisTemplate,
                issuanceLimiter,
                SESSION_TTL,
                NAMESPACE);
        request = MockServerHttpRequest.post("/api/genai/chatbot/help").build();
    }

    @Test
    void atomicallyIssuesNewAnonymousSession() {
        when(issuanceLimiter.tryIssue(
                eq(request),
                anyString(),
                eq(SESSION_TTL)))
                .thenReturn(Mono.just(
                        AnonymousSessionIssuanceLimiter.IssuanceResult.ISSUED));

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(null, request))
                .assertNext(sessionId -> assertTrue(sessionId.matches("[A-Za-z0-9_-]{43}")))
                .verifyComplete();

        verify(issuanceLimiter).tryIssue(eq(request), anyString(), eq(SESSION_TTL));
    }

    @Test
    void reusesOnlyCorrectMarkerWithoutExtendingAbsoluteTtl() {
        String sessionId = "A".repeat(43);
        when(valueOperations.get(KEY_PREFIX + sessionId))
                .thenReturn(Mono.just("source-fingerprint"));
        when(issuanceLimiter.isBoundToRequest(request, "source-fingerprint"))
                .thenReturn(true);

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(sessionId, request))
                .expectNext(sessionId)
                .verifyComplete();

        verify(valueOperations).get(KEY_PREFIX + sessionId);
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        verify(issuanceLimiter, never()).tryIssue(any(), anyString(), any(Duration.class));
    }

    @Test
    void replacesSessionWhenResolvedClientSourceDoesNotMatchMarker() {
        String clientSessionId = "E".repeat(43);
        ServerHttpRequest differentSourceRequest = MockServerHttpRequest
                .post("/api/genai/chatbot/help")
                .remoteAddress(new java.net.InetSocketAddress("203.0.113.9", 443))
                .build();
        when(valueOperations.get(KEY_PREFIX + clientSessionId))
                .thenReturn(Mono.just("original-source-fingerprint"));
        when(issuanceLimiter.tryIssue(
                eq(differentSourceRequest),
                anyString(),
                eq(SESSION_TTL)))
                .thenReturn(Mono.just(
                        AnonymousSessionIssuanceLimiter.IssuanceResult.ISSUED));

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(
                        clientSessionId,
                        differentSourceRequest))
                .assertNext(sessionId -> assertNotEquals(clientSessionId, sessionId))
                .verifyComplete();

        verify(issuanceLimiter).isBoundToRequest(
                differentSourceRequest,
                "original-source-fingerprint");
        verify(issuanceLimiter).tryIssue(
                eq(differentSourceRequest),
                anyString(),
                eq(SESSION_TTL));
    }

    @Test
    void replacesUnknownOrWrongMarkerSessionThroughAtomicIssuance() {
        String clientSessionId = "B".repeat(43);
        when(valueOperations.get(KEY_PREFIX + clientSessionId))
                .thenReturn(Mono.just("unexpected"));
        when(issuanceLimiter.tryIssue(
                eq(request),
                anyString(),
                eq(SESSION_TTL)))
                .thenReturn(Mono.just(
                        AnonymousSessionIssuanceLimiter.IssuanceResult.ISSUED));

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(
                        clientSessionId,
                        request))
                .assertNext(sessionId -> assertNotEquals(clientSessionId, sessionId))
                .verifyComplete();

        verify(issuanceLimiter).tryIssue(eq(request), anyString(), eq(SESSION_TTL));
    }

    @Test
    void malformedSessionRequiresIssuanceWithoutRedisReuseLookup() {
        when(issuanceLimiter.tryIssue(
                eq(request),
                anyString(),
                eq(SESSION_TTL)))
                .thenReturn(Mono.just(
                        AnonymousSessionIssuanceLimiter.IssuanceResult.ISSUED));

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(
                        "attacker-controlled",
                        request))
                .assertNext(sessionId -> assertNotEquals("attacker-controlled", sessionId))
                .verifyComplete();

        verify(valueOperations, never()).get(anyString());
        verify(issuanceLimiter).tryIssue(eq(request), anyString(), eq(SESSION_TTL));
    }

    @Test
    void deniedIssuanceReturnsTooManyRequests() {
        when(issuanceLimiter.tryIssue(
                eq(request),
                anyString(),
                eq(SESSION_TTL)))
                .thenReturn(Mono.just(
                        AnonymousSessionIssuanceLimiter.IssuanceResult.LIMIT_EXCEEDED));

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(null, request))
                .expectErrorSatisfies(error -> assertStatus(error, HttpStatus.TOO_MANY_REQUESTS))
                .verify();
    }

    @Test
    void retriesMarkerCollisionsWithoutTreatingThemAsQuotaConsumption() {
        when(issuanceLimiter.tryIssue(
                eq(request),
                anyString(),
                eq(SESSION_TTL)))
                .thenReturn(Mono.just(
                        AnonymousSessionIssuanceLimiter.IssuanceResult.COLLISION));

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(null, request))
                .expectErrorSatisfies(error -> assertStatus(error, HttpStatus.SERVICE_UNAVAILABLE))
                .verify();

        verify(issuanceLimiter, times(3)).tryIssue(
                eq(request),
                anyString(),
                eq(SESSION_TTL));
    }

    @Test
    void issuanceInfrastructureFailureRemainsServiceUnavailable() {
        when(issuanceLimiter.tryIssue(
                eq(request),
                anyString(),
                eq(SESSION_TTL)))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "redis unavailable")));

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(null, request))
                .expectErrorSatisfies(error -> assertStatus(error, HttpStatus.SERVICE_UNAVAILABLE))
                .verify();
    }

    @Test
    void expiredMarkerRequiresFreshRateLimitedIssuance() {
        String sessionId = "C".repeat(43);
        when(valueOperations.get(KEY_PREFIX + sessionId)).thenReturn(Mono.empty());
        when(issuanceLimiter.tryIssue(
                eq(request),
                anyString(),
                eq(SESSION_TTL)))
                .thenReturn(Mono.just(
                        AnonymousSessionIssuanceLimiter.IssuanceResult.ISSUED));

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(sessionId, request))
                .assertNext(replacement -> assertNotEquals(sessionId, replacement))
                .verifyComplete();

        verify(issuanceLimiter).tryIssue(eq(request), anyString(), eq(SESSION_TTL));
    }

    @Test
    void reuseRedisFailureReturnsServiceUnavailable() {
        String sessionId = "D".repeat(43);
        when(valueOperations.get(KEY_PREFIX + sessionId)).thenReturn(Mono.error(
                new DataAccessResourceFailureException("redis unavailable")));

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(sessionId, request))
                .expectErrorSatisfies(error -> assertStatus(error, HttpStatus.SERVICE_UNAVAILABLE))
                .verify();
    }

    @Test
    void generatedSessionRoundTripsThroughReuse() {
        when(issuanceLimiter.tryIssue(
                eq(request),
                anyString(),
                eq(SESSION_TTL)))
                .thenReturn(Mono.just(
                        AnonymousSessionIssuanceLimiter.IssuanceResult.ISSUED));
        String generatedSession = anonymousSessionService
                .getOrCreateAnonymousSession(null, request)
                .block();
        clearInvocations(issuanceLimiter, redisTemplate, valueOperations);
        when(valueOperations.get(KEY_PREFIX + generatedSession))
                .thenReturn(Mono.just("source-fingerprint"));
        when(issuanceLimiter.isBoundToRequest(request, "source-fingerprint"))
                .thenReturn(true);

        StepVerifier.create(anonymousSessionService.getOrCreateAnonymousSession(
                        generatedSession,
                        request))
                .expectNext(generatedSession)
                .verifyComplete();

        verify(issuanceLimiter, never()).tryIssue(any(), anyString(), any(Duration.class));
    }

    @Test
    void rejectsInvalidTtlAndNamespaceConfiguration() {
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionService(
                        redisTemplate,
                        issuanceLimiter,
                        Duration.ZERO,
                        NAMESPACE));
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionService(
                        redisTemplate,
                        issuanceLimiter,
                        Duration.ofNanos(1),
                        NAMESPACE));
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionService(
                        redisTemplate,
                        issuanceLimiter,
                        SESSION_TTL,
                        "bad{namespace}"));
    }

    private void assertStatus(Throwable error, HttpStatus expectedStatus) {
        ResponseStatusException statusException = (ResponseStatusException) error;
        assertEquals(expectedStatus, statusException.getStatusCode());
    }
}
