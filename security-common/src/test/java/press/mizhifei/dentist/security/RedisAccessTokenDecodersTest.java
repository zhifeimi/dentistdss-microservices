package press.mizhifei.dentist.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisAccessTokenDecodersTest {

    @Test
    void servletDecoderReadsSameSlotAccountAndFamilyStateAfterLocalValidation() {
        Jwt jwt = jwt(42L, 7L, "family-1");
        JwtDecoder delegate = token -> jwt;
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(List.of("7:1", "active"));

        Jwt decoded = new RedisAccessTokenJwtDecoder(delegate, redisTemplate).decode("signed-token");

        assertSame(jwt, decoded);
        verify(values).multiGet(List.of(
                "security:access:v1:{42}:account",
                "security:access:v1:{42}:family:family-1"));
    }

    @Test
    void servletDecoderRejectsDefiniteInactiveRevokedAndVersionMismatchState() {
        assertServletInvalid(List.of("7:0", "active"));
        assertServletInvalid(List.of("7:1", "revoked"));
        assertServletInvalid(List.of("8:1", "active"));
    }

    @Test
    void servletDecoderTreatsMissingMalformedAndRedisFailureAsUnavailable() {
        assertServletUnavailable(Arrays.asList(null, "active"));
        assertServletUnavailable(List.of("not-a-version:1", "active"));
        assertServletUnavailable(List.of("7:2", "active"));
        assertServletUnavailable(List.of("7:true", "active"));
        assertServletUnavailable(List.of("7:1", "unknown"));
        assertServletUnavailable(List.of("7:1", "ACTIVE"));
        assertServletUnavailable(List.of("7:1", "REVOKED"));

        JwtDecoder delegate = token -> jwt(42L, 7L, "family-1");
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenThrow(new RedisConnectionFailureException("redis.internal:6379"));

        AuthenticationServiceException exception = assertThrows(
                AuthenticationServiceException.class,
                () -> new RedisAccessTokenJwtDecoder(delegate, redisTemplate).decode("signed-token"));
        assertEquals("Access-token security state unavailable", exception.getMessage());
    }

    @Test
    void servletDecoderNeverQueriesRedisWhenLocalDecoderRejectsToken() {
        JwtDecoder delegate = token -> {
            throw new BadJwtException("bad signature");
        };
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        assertThrows(
                BadJwtException.class,
                () -> new RedisAccessTokenJwtDecoder(delegate, redisTemplate).decode("tampered"));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void reactiveDecoderReadsStateWithoutBlockingAndRejectsDefiniteInvalidState() {
        Jwt jwt = jwt(42L, 7L, "family-1");
        ReactiveJwtDecoder delegate = token -> Mono.just(jwt);
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(Mono.just(List.of("7:1", "active")));

        RedisAccessTokenReactiveJwtDecoder decoder = new RedisAccessTokenReactiveJwtDecoder(
                delegate,
                redisTemplate,
                Duration.ofSeconds(1));

        StepVerifier.create(decoder.decode("signed-token"))
                .expectNext(jwt)
                .verifyComplete();
        verify(values).multiGet(List.of(
                "security:access:v1:{42}:account",
                "security:access:v1:{42}:family:family-1"));

        when(values.multiGet(anyList())).thenReturn(Mono.just(List.of("7:1", "revoked")));
        StepVerifier.create(decoder.decode("signed-token"))
                .expectError(BadJwtException.class)
                .verify();
    }

    @Test
    void reactiveDecoderTreatsMissingMalformedErrorAndTimeoutAsUnavailable() {
        ReactiveJwtDecoder delegate = token -> Mono.just(jwt(42L, 7L, "family-1"));
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        RedisAccessTokenReactiveJwtDecoder decoder = new RedisAccessTokenReactiveJwtDecoder(
                delegate,
                redisTemplate,
                Duration.ofMillis(20));

        when(values.multiGet(anyList())).thenReturn(Mono.just(Arrays.asList("7:1", null)));
        assertReactiveUnavailable(decoder);

        when(values.multiGet(anyList())).thenReturn(Mono.just(List.of("bad:1", "active")));
        assertReactiveUnavailable(decoder);

        when(values.multiGet(anyList())).thenReturn(Mono.just(List.of("7:2", "active")));
        assertReactiveUnavailable(decoder);

        when(values.multiGet(anyList())).thenReturn(Mono.just(List.of("7:1", "ACTIVE")));
        assertReactiveUnavailable(decoder);

        when(values.multiGet(anyList())).thenReturn(Mono.just(List.of("7:1", "REVOKED")));
        assertReactiveUnavailable(decoder);

        when(values.multiGet(anyList())).thenReturn(Mono.error(
                new RedisConnectionFailureException("redis.internal:6379")));
        assertReactiveUnavailable(decoder);

        when(values.multiGet(anyList())).thenReturn(Mono.never());
        assertReactiveUnavailable(decoder);
    }

    @Test
    void reactiveDecoderNeverQueriesRedisWhenLocalDecoderRejectsToken() {
        ReactiveJwtDecoder delegate = token -> Mono.error(new BadJwtException("bad signature"));
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        RedisAccessTokenReactiveJwtDecoder decoder = new RedisAccessTokenReactiveJwtDecoder(
                delegate,
                redisTemplate,
                Duration.ofSeconds(1));

        StepVerifier.create(decoder.decode("tampered"))
                .expectError(BadJwtException.class)
                .verify();
        verifyNoInteractions(redisTemplate);
    }

    private void assertServletInvalid(List<String> state) {
        JwtDecoder delegate = token -> jwt(42L, 7L, "family-1");
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(state);

        assertThrows(
                BadJwtException.class,
                () -> new RedisAccessTokenJwtDecoder(delegate, redisTemplate).decode("signed-token"));
    }

    private void assertServletUnavailable(List<String> state) {
        JwtDecoder delegate = token -> jwt(42L, 7L, "family-1");
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(state);

        assertThrows(
                AuthenticationServiceException.class,
                () -> new RedisAccessTokenJwtDecoder(delegate, redisTemplate).decode("signed-token"));
    }

    private void assertReactiveUnavailable(RedisAccessTokenReactiveJwtDecoder decoder) {
        StepVerifier.create(decoder.decode("signed-token"))
                .expectErrorSatisfies(error -> {
                    assertEquals(AuthenticationServiceException.class, error.getClass());
                    assertEquals("Access-token security state unavailable", error.getMessage());
                })
                .verify();
    }

    private Jwt jwt(long userId, long securityVersion, String familyId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("signed-token")
                .header("alg", "RS256")
                .issuer("https://issuer.example")
                .audience(List.of("dentistdss-api"))
                .subject(Long.toString(userId))
                .issuedAt(now.minusSeconds(5))
                .notBefore(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300))
                .claim("jti", "token-id")
                .claim("tokenType", "access")
                .claim("securityVersion", securityVersion)
                .claim("sessionFamilyId", familyId)
                .build();
    }
}
