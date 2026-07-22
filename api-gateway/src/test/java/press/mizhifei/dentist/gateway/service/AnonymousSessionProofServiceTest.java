package press.mizhifei.dentist.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnonymousSessionProofServiceTest {

    private static final String SESSION_ID = "S".repeat(43);
    private static final String SOURCE_FINGERPRINT = "source-fingerprint";
    private static final Duration PROOF_TTL = Duration.ofSeconds(20);
    private static final int SOURCE_LIMIT = 30;
    private static final int GLOBAL_LIMIT = 3000;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String KEY_PREFIX = "gateway:anonymous-session:{test}:";

    private ReactiveStringRedisTemplate redisTemplate;
    private AnonymousSessionIssuanceLimiter issuanceLimiter;
    private AnonymousSessionProofService service;
    private ServerHttpRequest request;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        issuanceLimiter = mock(AnonymousSessionIssuanceLimiter.class);
        service = new AnonymousSessionProofService(
                redisTemplate,
                issuanceLimiter,
                PROOF_TTL,
                SOURCE_LIMIT,
                GLOBAL_LIMIT,
                WINDOW,
                "test");
        request = MockServerHttpRequest.post("/api/genai/chatbot/help").build();
        when(issuanceLimiter.sourceFingerprint(request)).thenReturn(SOURCE_FINGERPRINT);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void atomicallyMintsSourceBoundOneTimeProofInSharedClusterSlot() {
        whenExecuteReturns(1L);
        var keys = forClass(List.class);
        var arguments = forClass(Object[].class);

        StepVerifier.create(service.issueProof(request, SESSION_ID))
                .assertNext(proof -> {
                    assertTrue(proof.matches("[A-Za-z0-9_-]{43}"));
                    verify(redisTemplate).execute(
                            any(DefaultRedisScript.class),
                            keys.capture(),
                            arguments.capture());
                    assertEquals(List.of(
                                    KEY_PREFIX + "marker:" + SESSION_ID,
                                    KEY_PREFIX + "proof:" + proof,
                                    KEY_PREFIX + "proof-issuance:source:"
                                            + SOURCE_FINGERPRINT,
                                    KEY_PREFIX + "proof-issuance:global"),
                            keys.getValue());
                    assertArrayEquals(new Object[]{
                                    SOURCE_FINGERPRINT,
                                    SESSION_ID,
                                    Long.toString(PROOF_TTL.toMillis()),
                                    Integer.toString(SOURCE_LIMIT),
                                    Integer.toString(GLOBAL_LIMIT),
                                    Long.toString(WINDOW.toMillis())},
                            arguments.getValue());
                })
                .verifyComplete();
    }

    @Test
    void rejectsMissingOrWrongSourceBinding() {
        whenExecuteReturns(0L);

        StepVerifier.create(service.issueProof(request, SESSION_ID))
                .expectErrorSatisfies(error -> assertStatus(error, HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void rejectsProofBurstWhenSourceOrGlobalLimitIsExhausted() {
        whenExecuteReturns(3L);

        StepVerifier.create(service.issueProof(request, SESSION_ID))
                .expectErrorSatisfies(error -> assertStatus(
                        error, HttpStatus.TOO_MANY_REQUESTS))
                .verify();
    }

    @Test
    void retriesProofCollision() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.just(2L), Flux.just(1L));

        StepVerifier.create(service.issueProof(request, SESSION_ID))
                .assertNext(proof -> assertTrue(proof.matches("[A-Za-z0-9_-]{43}")))
                .verifyComplete();

        verify(redisTemplate, times(2)).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class));
    }

    @Test
    void exhaustedProofCollisionsReturnServiceUnavailable() {
        whenExecuteReturns(2L);

        StepVerifier.create(service.issueProof(request, SESSION_ID))
                .expectErrorSatisfies(error -> assertStatus(
                        error, HttpStatus.SERVICE_UNAVAILABLE))
                .verify();

        verify(redisTemplate, times(3)).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class));
    }

    @Test
    void mapsRedisFailureAndMissingOrUnexpectedResultsToServiceUnavailable() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("redis unavailable")));
        StepVerifier.create(service.issueProof(request, SESSION_ID))
                .expectErrorSatisfies(error -> assertStatus(
                        error, HttpStatus.SERVICE_UNAVAILABLE))
                .verify();

        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.empty());
        StepVerifier.create(service.issueProof(request, SESSION_ID))
                .expectErrorSatisfies(error -> assertStatus(
                        error, HttpStatus.SERVICE_UNAVAILABLE))
                .verify();

        whenExecuteReturns(99L);
        StepVerifier.create(service.issueProof(request, SESSION_ID))
                .expectErrorSatisfies(error -> assertStatus(
                        error, HttpStatus.SERVICE_UNAVAILABLE))
                .verify();
    }

    @Test
    void rejectsInvalidInputAndConfiguration() {
        StepVerifier.create(service.issueProof(request, "malformed"))
                .expectError(IllegalArgumentException.class)
                .verify();
        assertInvalidConfiguration(
                Duration.ZERO,
                SOURCE_LIMIT,
                GLOBAL_LIMIT,
                WINDOW,
                "test");
        assertInvalidConfiguration(
                Duration.ofNanos(1),
                SOURCE_LIMIT,
                GLOBAL_LIMIT,
                WINDOW,
                "test");
        assertInvalidConfiguration(
                PROOF_TTL,
                0,
                GLOBAL_LIMIT,
                WINDOW,
                "test");
        assertInvalidConfiguration(
                PROOF_TTL,
                SOURCE_LIMIT,
                0,
                WINDOW,
                "test");
        assertInvalidConfiguration(
                PROOF_TTL,
                SOURCE_LIMIT,
                GLOBAL_LIMIT,
                Duration.ZERO,
                "test");
        assertInvalidConfiguration(
                PROOF_TTL,
                SOURCE_LIMIT,
                GLOBAL_LIMIT,
                WINDOW,
                "bad{namespace}");
    }

    private void assertInvalidConfiguration(
            Duration proofTtl,
            int sourceLimit,
            int globalLimit,
            Duration window,
            String namespace) {
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionProofService(
                        redisTemplate,
                        issuanceLimiter,
                        proofTtl,
                        sourceLimit,
                        globalLimit,
                        window,
                        namespace));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void whenExecuteReturns(long result) {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.just(result));
    }

    private void assertStatus(Throwable error, HttpStatus expectedStatus) {
        ResponseStatusException statusException = (ResponseStatusException) error;
        assertEquals(expectedStatus, statusException.getStatusCode());
    }
}
