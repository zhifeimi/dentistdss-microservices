package press.mizhifei.dentist.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import press.mizhifei.dentist.auth.model.AuthSecurityOutbox;
import press.mizhifei.dentist.auth.repository.AuthSecurityOutboxRepository;
import press.mizhifei.dentist.auth.repository.AuthSessionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecurityStateOutboxServiceTest {

    private AuthSecurityOutboxRepository outboxRepository;
    private AuthSessionRepository sessionRepository;
    private SecurityStateService securityStateService;
    private SecurityStateOutboxService service;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(AuthSecurityOutboxRepository.class);
        sessionRepository = mock(AuthSessionRepository.class);
        securityStateService = mock(SecurityStateService.class);
        service = new SecurityStateOutboxService(outboxRepository, sessionRepository, securityStateService);
        ReflectionTestUtils.setField(service, "outboxEnabled", true);
        ReflectionTestUtils.setField(service, "retryBackoffMs", 30_000L);
        ReflectionTestUtils.setField(service, "replayBatchSize", 100);
        ReflectionTestUtils.setField(service, "sweepEnabled", true);
        ReflectionTestUtils.setField(service, "sweepBatchSize", 500);
        ReflectionTestUtils.setField(service, "refreshTokenDays", 30L);
        ReflectionTestUtils.setField(service, "accessTokenExpirationMs", 300_000L);
        ReflectionTestUtils.setField(service, "jwtClockSkewSeconds", 60L);
    }

    @Test
    void enqueueUpsertsAPendingMarkerWithTheTombstoneExpiry() {
        LocalDateTime createdAt = LocalDateTime.now();
        Duration ttl = Duration.ofDays(30);

        service.enqueue(42L, "family-1", createdAt, ttl);

        verify(outboxRepository).upsertPending(
                42L,
                "family-1",
                ttl.toMillis(),
                createdAt,
                createdAt.plus(ttl));
    }

    @Test
    void markPublishedDropsThePendingMarker() {
        service.markPublished(42L, "family-1");

        verify(outboxRepository).deleteByUserIdAndFamilyId(42L, "family-1");
    }

    @Test
    void replayPublishesEveryPendingRowAndDeletesTheSuccessfulOnes() {
        AuthSecurityOutbox first = pendingRow(1L, 42L, "family-a");
        AuthSecurityOutbox second = pendingRow(2L, 42L, "family-b");
        when(outboxRepository.findPending(any(LocalDateTime.class), any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(List.of(first, second));

        service.replayPendingPublications();

        verify(securityStateService).revokeFamily(
                42L, "family-a", Duration.ofMillis(first.getTombstoneTtlMillis()));
        verify(securityStateService).revokeFamily(
                42L, "family-b", Duration.ofMillis(second.getTombstoneTtlMillis()));
        verify(outboxRepository).delete(first);
        verify(outboxRepository).delete(second);
        verify(outboxRepository).deleteExpired(any(LocalDateTime.class));
    }

    @Test
    void replayKeepsFailedRowsAndRecordsTheAttempt() {
        AuthSecurityOutbox failing = pendingRow(1L, 42L, "family-a");
        when(outboxRepository.findPending(any(LocalDateTime.class), any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(List.of(failing));
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(securityStateService)
                .revokeFamily(eq(42L), eq("family-a"), any(Duration.class));

        service.replayPendingPublications();

        org.junit.jupiter.api.Assertions.assertEquals(1, failing.getAttempts());
        org.junit.jupiter.api.Assertions.assertNotNull(failing.getLastAttemptAt());
        org.junit.jupiter.api.Assertions.assertTrue(failing.getLastError().contains("redis unavailable"));
        verify(outboxRepository).save(failing);
        verify(outboxRepository, never()).delete(failing);
    }

    @Test
    void replayDoesNothingWhenTheOutboxIsDisabled() {
        ReflectionTestUtils.setField(service, "outboxEnabled", false);

        service.replayPendingPublications();

        verifyNoInteractions(securityStateService);
        verify(outboxRepository, never()).findPending(any(), any(), any());
    }

    @Test
    void replaySurvivesAnUnreadableOutboxTable() {
        when(outboxRepository.findPending(any(LocalDateTime.class), any(LocalDateTime.class), any(Limit.class)))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        service.replayPendingPublications();

        verifyNoInteractions(securityStateService);
    }

    @Test
    void reconcileReEnqueuesRevokedFamiliesMissingRedisState() {
        when(sessionRepository.findDistinctRevokedUnexpiredFamilies(any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{42L, "family-a"},
                        new Object[]{42L, "family-b"}));
        when(securityStateService.findFamiliesWithoutState(any()))
                .thenReturn(List.of(new SecurityStateService.FamilyRef(42L, "family-a")));

        service.reconcileRevokedFamilies();

        verify(outboxRepository).upsertPending(
                eq(42L),
                eq("family-a"),
                any(Long.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class));
    }

    @Test
    void reconcileSkipsEnqueueWhenEveryRevokedFamilyHasState() {
        when(sessionRepository.findDistinctRevokedUnexpiredFamilies(any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(List.<Object[]>of(new Object[]{42L, "family-a"}));
        when(securityStateService.findFamiliesWithoutState(any())).thenReturn(List.of());

        service.reconcileRevokedFamilies();

        verify(outboxRepository, never()).upsertPending(
                any(Long.class), anyString(), any(Long.class), any(), any());
    }

    @Test
    void reconcileSwallowsRedisFailuresForTheNextSweep() {
        when(sessionRepository.findDistinctRevokedUnexpiredFamilies(any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(List.<Object[]>of(new Object[]{42L, "family-a"}));
        when(securityStateService.findFamiliesWithoutState(any()))
                .thenThrow(new SecurityStateService.SecurityStateUnavailableException());

        service.reconcileRevokedFamilies();

        verify(outboxRepository, never()).upsertPending(
                any(Long.class), anyString(), any(Long.class), any(), any());
    }

    @Test
    void reconcileDoesNothingWhenTheSweepIsDisabled() {
        ReflectionTestUtils.setField(service, "sweepEnabled", false);

        service.reconcileRevokedFamilies();

        verify(sessionRepository, never()).findDistinctRevokedUnexpiredFamilies(any(), any());
    }

    private AuthSecurityOutbox pendingRow(long id, long userId, String familyId) {
        return AuthSecurityOutbox.builder()
                .id(id)
                .userId(userId)
                .familyId(familyId)
                .tombstoneTtlMillis(Duration.ofDays(30).toMillis())
                .createdAt(LocalDateTime.now().minusMinutes(5))
                .attempts(0)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build();
    }
}
