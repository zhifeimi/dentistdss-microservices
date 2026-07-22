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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnonymousSessionRegistryTest {

    private static final String SESSION_ID = "S".repeat(43);
    private static final String PROOF = "P".repeat(43);
    private static final String SOURCE_FINGERPRINT = "a".repeat(64);
    private static final String KEY_PREFIX = "gateway:anonymous-session:{test}:";

    private ReactiveStringRedisTemplate redisTemplate;
    private AnonymousSessionRegistry sessionRegistry;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        sessionRegistry = new AnonymousSessionRegistry(redisTemplate, "test");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void atomicallyConsumesProofAndReturnsStableAnonymousQuotaPrincipal() {
        whenExecuteReturns(SOURCE_FINGERPRINT);
        var keys = forClass(List.class);
        var arguments = forClass(Object[].class);

        StepVerifier.create(sessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF))
                .assertNext(session -> {
                    assertEquals(SESSION_ID, session.sessionId());
                    assertEquals(SOURCE_FINGERPRINT, session.sourceFingerprint());
                })
                .verifyComplete();

        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                keys.capture(),
                arguments.capture());
        assertEquals(List.of(
                        KEY_PREFIX + "marker:" + SESSION_ID,
                        KEY_PREFIX + "proof:" + PROOF),
                keys.getValue());
        assertArrayEquals(new Object[]{SESSION_ID}, arguments.getValue());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsMissingOrMalformedSessionAndProofWithoutRedisAccess() {
        assertForbidden(sessionRegistry.requireGatewayIssuedSession(null, PROOF));
        assertForbidden(sessionRegistry.requireGatewayIssuedSession("not-a-session", PROOF));
        assertForbidden(sessionRegistry.requireGatewayIssuedSession(SESSION_ID, null));
        assertForbidden(sessionRegistry.requireGatewayIssuedSession(SESSION_ID, "not-a-proof"));

        verify(redisTemplate, never()).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsReplayedProofAfterFirstSuccessfulConsumption() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.just(SOURCE_FINGERPRINT), Flux.just("invalid"));

        StepVerifier.create(sessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF))
                .expectNextCount(1)
                .verifyComplete();
        assertForbidden(sessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF));
    }

    @Test
    void rejectsMissingWrongOrAlreadyConsumedProof() {
        whenExecuteReturns("invalid");

        assertForbidden(sessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mapsRedisFailureMissingResultAndMalformedMarkerToServiceUnavailable() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("redis unavailable")));
        assertServiceUnavailable(sessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF));

        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.empty());
        assertServiceUnavailable(sessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF));

        whenExecuteReturns("unexpected-marker");
        assertServiceUnavailable(sessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF));
    }

    @Test
    void rejectsInvalidNamespaceConfiguration() {
        assertThrows(IllegalArgumentException.class, () ->
                new AnonymousSessionRegistry(redisTemplate, "bad{namespace}"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void whenExecuteReturns(String value) {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)))
                .thenReturn(Flux.just(value));
    }

    private void assertForbidden(
            Mono<AnonymousSessionRegistry.VerifiedAnonymousSession> validation) {
        StepVerifier.create(validation)
                .expectErrorSatisfies(error -> assertStatus(error, HttpStatus.FORBIDDEN))
                .verify();
    }

    private void assertServiceUnavailable(
            Mono<AnonymousSessionRegistry.VerifiedAnonymousSession> validation) {
        StepVerifier.create(validation)
                .expectErrorSatisfies(error -> assertStatus(
                        error, HttpStatus.SERVICE_UNAVAILABLE))
                .verify();
    }

    private void assertStatus(Throwable error, HttpStatus expectedStatus) {
        ResponseStatusException statusException = (ResponseStatusException) error;
        assertEquals(expectedStatus, statusException.getStatusCode());
    }
}
