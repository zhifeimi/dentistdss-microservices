package press.mizhifei.dentist.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityStateServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SecurityStateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new SecurityStateService(redisTemplate);
    }

    @Test
    void usesClusterCompatiblePerUserAccessStateKeys() {
        assertEquals(
                "security:access:v1:{42}:account",
                SecurityStateService.accountKey(42L));
        assertEquals(
                "security:access:v1:{42}:family:family-1",
                SecurityStateService.familyKey(42L, "family-1"));
    }

    @Test
    void publishesMonotonicAccountVersionAndActiveFlag() {
        when(redisTemplate.execute(any(), anyList(), any(), any()))
                .thenReturn(1L);

        service.publishAccountState(42L, 7L, true);

        verify(redisTemplate).execute(
                any(),
                eq(List.of("security:access:v1:{42}:account")),
                eq("7"),
                eq("1"));
    }

    @Test
    void refusesToOverwriteNewerAccountState() {
        when(redisTemplate.execute(any(), anyList(), any(), any()))
                .thenReturn(0L);

        assertThrows(IllegalStateException.class, () ->
                service.publishAccountState(42L, 6L, true));
    }

    @Test
    void parsesOnlyPositiveVersionedAccountState() {
        when(valueOperations.get("security:access:v1:{42}:account"))
                .thenReturn("7:1", "0:1", "7:true", null);

        assertEquals(
                Optional.of(new SecurityStateService.AccountSecurityState(7L, true)),
                service.readAccountState(42L));
        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.readAccountState(42L));
        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.readAccountState(42L));
        assertTrue(service.readAccountState(42L).isEmpty());
    }

    @Test
    void activationAndRefreshExtensionHonorAtomicScriptResult() {
        when(redisTemplate.execute(any(), anyList(), any()))
                .thenReturn(0L, 1L);

        assertFalse(service.activateFamily(42L, "revoked-family", Duration.ofDays(30)));
        assertTrue(service.extendActiveFamily(42L, "active-family", Duration.ofDays(30)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void familyActivationUsesAbsentOnlySetWithExpiry() {
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(1L);
        ArgumentCaptor<DefaultRedisScript> scriptCaptor =
                ArgumentCaptor.forClass(DefaultRedisScript.class);

        assertTrue(service.activateFamily(42L, "family-1", Duration.ofSeconds(90)));

        verify(redisTemplate).execute(
                scriptCaptor.capture(),
                eq(List.of("security:access:v1:{42}:family:family-1")),
                eq("90000"));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("'SET', KEYS[1], 'active', 'PX', ARGV[1], 'NX'"));
    }

    @Test
    void parsesOnlyExactLowercaseFamilyStates() {
        when(valueOperations.get("security:access:v1:{42}:family:family-1"))
                .thenReturn("active", "revoked", "ACTIVE", "Revoked", "unknown", null);

        assertEquals(Optional.of(SecurityStateService.FamilySecurityState.ACTIVE),
                service.readFamilyState(42L, "family-1"));
        assertEquals(Optional.of(SecurityStateService.FamilySecurityState.REVOKED),
                service.readFamilyState(42L, "family-1"));
        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.readFamilyState(42L, "family-1"));
        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.readFamilyState(42L, "family-1"));
        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.readFamilyState(42L, "family-1"));
        assertTrue(service.readFamilyState(42L, "family-1").isEmpty());
    }

    @Test
    void revokedTombstoneUsesSameUserHashSlotAndConfiguredLifetime() {
        Duration lifetime = Duration.ofDays(30).plusMinutes(5);

        service.revokeFamily(42L, "family-1", lifetime);

        verify(valueOperations).set(
                "security:access:v1:{42}:family:family-1",
                "revoked",
                lifetime);
    }
}
