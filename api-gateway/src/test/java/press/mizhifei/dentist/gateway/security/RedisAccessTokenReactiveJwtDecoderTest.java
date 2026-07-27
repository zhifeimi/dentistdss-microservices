package press.mizhifei.dentist.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisAccessTokenReactiveJwtDecoderTest {

    private ReactiveJwtDecoder delegate;
    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valueOperations;
    private RedisAccessTokenReactiveJwtDecoder decoder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        delegate = mock(ReactiveJwtDecoder.class);
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        valueOperations = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        decoder = new RedisAccessTokenReactiveJwtDecoder(
                delegate,
                redisTemplate,
                Duration.ofSeconds(1));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void validatesCurrentAccountAndFamilyUsingClusterCompatibleKeys() {
        Jwt jwt = jwt("42", 7L, "family-1");
        when(delegate.decode("bearer-token")).thenReturn(Mono.just(jwt));
        when(valueOperations.multiGet(anyCollection()))
                .thenReturn(Mono.just(List.of("7:1", "active")));
        var keys = forClass(Collection.class);

        StepVerifier.create(decoder.decode("bearer-token"))
                .assertNext(decoded -> assertSame(jwt, decoded))
                .verifyComplete();

        verify(valueOperations).multiGet(keys.capture());
        assertEquals(List.of(
                        "security:access:v1:{42}:account",
                        "security:access:v1:{42}:family:family-1"),
                List.copyOf(keys.getValue()));
    }

    @Test
    void rejectsDefiniteInactiveRevokedAndVersionMismatchStateAsInvalidToken() {
        assertInvalidState("7:0", "active");
        assertInvalidState("8:1", "active");
        assertInvalidState("7:1", "revoked");
    }

    @Test
    void mapsMissingMalformedAndFailedRedisStateToAuthenticationServiceException() {
        assertUnavailableState(Mono.empty());
        assertUnavailableState(Mono.just(Arrays.asList(null, "active")));
        assertUnavailableState(Mono.just(Arrays.asList("7:1", null)));
        assertUnavailableState(Mono.just(List.of("not-a-version:1", "active")));
        assertUnavailableState(Mono.just(List.of("7:2", "active")));
        assertUnavailableState(Mono.just(List.of("7:1", "unknown")));
        assertUnavailableState(Mono.just(List.of("7:1", "ACTIVE")));
        assertUnavailableState(Mono.just(List.of("7:1", "REVOKED")));
        assertUnavailableState(Mono.error(
                new DataAccessResourceFailureException("redis unavailable")));
    }

    @Test
    void mapsRedisTimeoutToAuthenticationServiceException() {
        Jwt jwt = jwt("42", 7L, "family-1");
        when(delegate.decode("bearer-token")).thenReturn(Mono.just(jwt));
        when(valueOperations.multiGet(anyCollection())).thenReturn(Mono.never());
        decoder = new RedisAccessTokenReactiveJwtDecoder(
                delegate,
                redisTemplate,
                Duration.ofMillis(10));

        StepVerifier.create(decoder.decode("bearer-token"))
                .expectError(AuthenticationServiceException.class)
                .verify(Duration.ofSeconds(1));
    }

    @Test
    void localJwtFailureAndMalformedRequiredClaimsNeverReadRedis() {
        BadJwtException localFailure = new BadJwtException("locally invalid");
        when(delegate.decode("locally-invalid"))
                .thenReturn(Mono.error(localFailure));

        StepVerifier.create(decoder.decode("locally-invalid"))
                .expectErrorSatisfies(error -> assertSame(localFailure, error))
                .verify();
        verifyNoInteractions(redisTemplate);

        when(delegate.decode("invalid-subject"))
                .thenReturn(Mono.just(jwt("not-numeric", 7L, "family-1")));
        StepVerifier.create(decoder.decode("invalid-subject"))
                .expectError(BadJwtException.class)
                .verify();

        when(delegate.decode("missing-version"))
                .thenReturn(Mono.just(jwt("42", null, "family-1")));
        StepVerifier.create(decoder.decode("missing-version"))
                .expectError(BadJwtException.class)
                .verify();

        when(delegate.decode("zero-version"))
                .thenReturn(Mono.just(jwt("42", 0L, "family-1")));
        StepVerifier.create(decoder.decode("zero-version"))
                .expectError(BadJwtException.class)
                .verify();

        when(delegate.decode("string-version"))
                .thenReturn(Mono.just(jwt("42", "7", "family-1")));
        StepVerifier.create(decoder.decode("string-version"))
                .expectError(BadJwtException.class)
                .verify();

        when(delegate.decode("blank-family"))
                .thenReturn(Mono.just(jwt("42", 7L, " ")));
        StepVerifier.create(decoder.decode("blank-family"))
                .expectError(BadJwtException.class)
                .verify();

        verify(redisTemplate, never()).opsForValue();
    }

    private void assertInvalidState(String accountState, String familyState) {
        Jwt jwt = jwt("42", 7L, "family-1");
        when(delegate.decode("bearer-token")).thenReturn(Mono.just(jwt));
        when(valueOperations.multiGet(anyCollection()))
                .thenReturn(Mono.just(List.of(accountState, familyState)));

        StepVerifier.create(decoder.decode("bearer-token"))
                .expectError(BadJwtException.class)
                .verify();
    }

    private void assertUnavailableState(Mono<List<String>> state) {
        Jwt jwt = jwt("42", 7L, "family-1");
        when(delegate.decode("bearer-token")).thenReturn(Mono.just(jwt));
        when(valueOperations.multiGet(anyCollection())).thenReturn(state);

        StepVerifier.create(decoder.decode("bearer-token"))
                .expectErrorSatisfies(error -> {
                    assertEquals(AuthenticationServiceException.class, error.getClass());
                    assertEquals("Access-token security state unavailable", error.getMessage());
                })
                .verify();
    }

    private Jwt jwt(String subject, Object securityVersion, String sessionFamilyId) {
        Jwt.Builder builder = Jwt.withTokenValue("verified-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("jti", "token-id")
                .claim("tokenType", "access")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (securityVersion != null) {
            builder.claim("securityVersion", securityVersion);
        }
        if (sessionFamilyId != null) {
            builder.claim("sessionFamilyId", sessionFamilyId);
        }
        return builder.build();
    }
}
