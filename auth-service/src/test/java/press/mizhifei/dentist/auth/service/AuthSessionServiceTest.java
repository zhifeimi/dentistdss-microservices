package press.mizhifei.dentist.auth.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import press.mizhifei.dentist.auth.dto.LoginRequest;
import press.mizhifei.dentist.auth.model.AuthSession;
import press.mizhifei.dentist.auth.model.Role;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.repository.AuthSessionRepository;
import press.mizhifei.dentist.auth.repository.UserRepository;
import press.mizhifei.dentist.auth.security.JwtTokenProvider;
import press.mizhifei.dentist.auth.security.UserPrincipal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {

    private AuthenticationManager authenticationManager;
    private UserRepository userRepository;
    private AuthSessionRepository sessionRepository;
    private AuthSessionRevocationService revocationService;
    private JwtTokenProvider tokenProvider;
    private SecurityStateService securityStateService;
    private AuthSessionService service;
    private User user;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        userRepository = mock(UserRepository.class);
        sessionRepository = mock(AuthSessionRepository.class);
        revocationService = mock(AuthSessionRevocationService.class);
        tokenProvider = mock(JwtTokenProvider.class);
        securityStateService = mock(SecurityStateService.class);
        service = new AuthSessionService(
                authenticationManager,
                userRepository,
                sessionRepository,
                revocationService,
                tokenProvider,
                securityStateService);
        ReflectionTestUtils.setField(service, "refreshTokenDays", 30L);
        ReflectionTestUtils.setField(service, "accessTokenExpirationMs", 300_000L);
        ReflectionTestUtils.setField(service, "jwtClockSkewSeconds", 60L);

        user = User.builder()
                .id(42L)
                .email("patient@example.com")
                .password("password-hash")
                .firstName("Test")
                .lastName("Patient")
                .roles(Set.of(Role.PATIENT))
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
        UserPrincipal principal = UserPrincipal.create(user);
        authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities());

        when(sessionRepository.save(any(AuthSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionRepository.rotateIfActive(
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyString()))
                .thenReturn(1);
        when(tokenProvider.generateToken(any(Authentication.class), anyString()))
                .thenReturn("access-token");
        when(securityStateService.randomToken(48)).thenReturn("refresh-token");
        when(securityStateService.randomToken(32)).thenReturn("csrf-token");
        when(securityStateService.activateFamily(eq(42L), anyString(), any()))
                .thenReturn(true);
        when(securityStateService.extendActiveFamily(eq(42L), anyString(), any()))
                .thenReturn(true);
        when(securityStateService.readFamilyState(eq(42L), anyString()))
                .thenReturn(Optional.of(SecurityStateService.FamilySecurityState.ACTIVE));
        when(securityStateService.readAccountState(42L))
                .thenReturn(Optional.of(new SecurityStateService.AccountSecurityState(1L, true)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void passwordLoginSignsTheNewPersistedSessionFamily() {
        when(userRepository.findByEmailForUpdate("patient@example.com"))
                .thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authentication);

        service.authenticate(new LoginRequest("patient@example.com", "password"));

        AuthSession persisted = persistedNewSession();
        assertNotNull(UUID.fromString(persisted.getFamilyId()));
        verify(tokenProvider).generateToken(authentication, persisted.getFamilyId());
    }

    @Test
    void oauthLoginSignsTheNewPersistedSessionFamily() {
        service.issueForUser(user);

        AuthSession persisted = persistedNewSession();
        assertNotNull(UUID.fromString(persisted.getFamilyId()));
        verify(tokenProvider).generateToken(
                any(Authentication.class),
                eq(persisted.getFamilyId()));
    }

    @Test
    void refreshRotationPreservesTheExistingSessionFamilyClaim() {
        AuthSession current = AuthSession.builder()
                .userId(42L)
                .familyId("existing-family")
                .securityVersion(1L)
                .tokenHash("current-token-hash")
                .createdAt(LocalDateTime.now().minusDays(1))
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(current));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.refresh("current-refresh-token");

        verify(sessionRepository).rotateIfActive(
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyString());
        verify(tokenProvider).generateToken(
                any(Authentication.class),
                eq("existing-family"));
    }

    @Test
    void rejectedReuseCommitsFamilyRevocation() throws Exception {
        AuthSession reused = AuthSession.builder()
                .userId(42L)
                .familyId("compromised-family")
                .tokenHash("reused-token-hash")
                .createdAt(LocalDateTime.now().minusDays(2))
                .expiresAt(LocalDateTime.now().plusDays(1))
                .revokedAt(LocalDateTime.now().minusDays(1))
                .build();
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(reused));

        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
                () -> service.refresh("reused-refresh-token"));

        verify(sessionRepository).revokeActiveByUserIdAndFamilyId(eq(42L),
                eq("compromised-family"),
                any(LocalDateTime.class));
        Transactional transactional = AuthSessionService.class
                .getMethod("refresh", String.class)
                .getAnnotation(Transactional.class);
        assertTrue(java.util.Arrays.asList(transactional.noRollbackFor())
                .contains(org.springframework.security.authentication.BadCredentialsException.class));
    }

    @Test
    void failedAtomicClaimRevokesFamilyAsConcurrentReuse() {
        AuthSession current = activeSession("concurrent-family");
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(current));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(sessionRepository.rotateIfActive(
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyString()))
                .thenReturn(0);

        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
                () -> service.refresh("concurrently-reused-token"));

        verify(sessionRepository).revokeActiveByUserIdAndFamilyId(eq(42L),
                eq("concurrent-family"),
                any(LocalDateTime.class));
    }

    @Test
    void lockedAccountRevokesRefreshFamilyWithoutRotation() {
        user.setAccountNonLocked(false);
        AuthSession current = activeSession("locked-family");
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(current));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
                () -> service.refresh("locked-account-token"));

        verify(securityStateService).revokeFamily(eq(42L), eq("locked-family"), any());
        verify(sessionRepository).revokeActiveByUserIdAndFamilyId(eq(42L),
                eq("locked-family"),
                any(LocalDateTime.class));
        verify(sessionRepository, never()).rotateIfActive(
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyString());
    }

    @Test
    void loginRejectsAuthenticationSnapshotThatRacedSecurityMutation() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authentication);
        user.setSecurityVersion(2L);
        when(userRepository.findByEmailForUpdate("patient@example.com"))
                .thenReturn(Optional.of(user));

        assertThrows(BadCredentialsException.class,
                () -> service.authenticate(new LoginRequest("patient@example.com", "old-password")));

        verify(sessionRepository, never()).save(any());
        verify(securityStateService, never()).activateFamily(anyLong(), anyString(), any());
    }

    @Test
    void newLoginPublishesAccountAndFamilyBeforeReturningAccessToken() {
        when(userRepository.findByEmailForUpdate("patient@example.com"))
                .thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authentication);

        service.authenticate(new LoginRequest("patient@example.com", "password"));

        verify(securityStateService).publishAccountState(42L, 1L, true);
        verify(securityStateService).activateFamily(eq(42L), anyString(), any());
        var ordered = inOrder(securityStateService, tokenProvider);
        ordered.verify(securityStateService).publishAccountState(42L, 1L, true);
        ordered.verify(securityStateService).activateFamily(eq(42L), anyString(), any());
        ordered.verify(tokenProvider).generateToken(eq(authentication), anyString());
    }

    @Test
    void refreshRejectsMissingFamilyStateWithoutRotation() {
        AuthSession current = activeSession("missing-family");
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(current));
        when(securityStateService.readFamilyState(42L, "missing-family"))
                .thenReturn(Optional.empty());

        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.refresh("missing-family-token"));

        verify(securityStateService, never()).revokeFamily(eq(42L), eq("missing-family"), any());
        verify(sessionRepository, never()).rotateIfActive(
                anyString(), any(), any(), anyString());
    }

    @Test
    void refreshRejectsRevokedFamilyWithoutReactivation() {
        AuthSession current = activeSession("revoked-family");
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(current));
        when(securityStateService.readFamilyState(42L, "revoked-family"))
                .thenReturn(Optional.of(SecurityStateService.FamilySecurityState.REVOKED));

        assertThrows(BadCredentialsException.class,
                () -> service.refresh("revoked-family-token"));

        verify(securityStateService, never()).extendActiveFamily(anyLong(), anyString(), any());
        verify(securityStateService, never()).activateFamily(anyLong(), anyString(), any());
        verify(securityStateService).revokeFamily(eq(42L), eq("revoked-family"), any());
    }

    @Test
    void refreshRevokesFamilyWhenActiveExtensionLosesToRevocation() {
        AuthSession current = activeSession("racing-family");
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(current));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(securityStateService.extendActiveFamily(42L, "racing-family", serviceFamilyLifetime()))
                .thenReturn(false);

        assertThrows(BadCredentialsException.class,
                () -> service.refresh("racing-token"));

        verify(sessionRepository).rotateIfActive(anyString(), any(), any(), anyString());
        verify(securityStateService).revokeFamily(eq(42L), eq("racing-family"), any());
        verify(tokenProvider, never()).generateToken(any(), anyString());
    }

    @Test
    void logoutRedisFailureOccursOnlyAfterDurableDatabaseRevocation() {
        RedisConnectionFailureException redisFailure =
                new RedisConnectionFailureException("redis unavailable");
        doThrow(redisFailure).when(securityStateService)
                .revokeFamily(eq(42L), eq("family-1"), any());

        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.revokeFamily(42L, "family-1"));

        var ordered = inOrder(revocationService, securityStateService);
        ordered.verify(revocationService).revokeFamily(
                eq(42L), eq("family-1"), any(LocalDateTime.class));
        ordered.verify(securityStateService).revokeFamily(
                eq(42L), eq("family-1"), any());
    }

    @Test
    void refreshTokenLogoutCommitsDatabaseFamilyBeforeRedisFailure() {
        AuthSession current = activeSession("logout-family");
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(current));
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(securityStateService)
                .revokeFamily(eq(42L), eq("logout-family"), any());

        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.revoke("logout-token"));

        var ordered = inOrder(revocationService, securityStateService);
        ordered.verify(revocationService).revokeFamily(
                eq(42L), eq("logout-family"), any(LocalDateTime.class));
        ordered.verify(securityStateService).revokeFamily(
                eq(42L), eq("logout-family"), any());
    }

    @Test
    void refreshExtensionRedisFailureRevokesDatabaseFamilyWithoutReplacement() {
        AuthSession current = activeSession("extension-failure-family");
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(current));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(securityStateService.extendActiveFamily(
                42L, "extension-failure-family", serviceFamilyLifetime()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.refresh("extension-failure-token"));

        verify(sessionRepository).rotateIfActive(anyString(), any(), any(), anyString());
        verify(sessionRepository).revokeActiveByUserIdAndFamilyId(
                eq(42L), eq("extension-failure-family"), any(LocalDateTime.class));
        verify(revocationService, never()).revokeFamily(anyLong(), anyString(), any());
        verify(sessionRepository, never()).save(any(AuthSession.class));
        verify(tokenProvider, never()).generateToken(any(), anyString());
    }

    @Test
    void failedExtensionAndTombstonePublicationCommitRevocationWithoutReplacement()
            throws Exception {
        AuthSession current = activeSession("lost-race-family");
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(current));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(securityStateService.extendActiveFamily(
                42L, "lost-race-family", serviceFamilyLifetime()))
                .thenReturn(false);
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(securityStateService)
                .revokeFamily(eq(42L), eq("lost-race-family"), any());

        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.refresh("lost-race-token"));

        verify(sessionRepository).revokeActiveByUserIdAndFamilyId(
                eq(42L), eq("lost-race-family"), any(LocalDateTime.class));
        verify(sessionRepository, never()).save(any(AuthSession.class));
        verify(tokenProvider, never()).generateToken(any(), anyString());
        Transactional transactional = AuthSessionService.class
                .getMethod("refresh", String.class)
                .getAnnotation(Transactional.class);
        assertTrue(java.util.Arrays.asList(transactional.noRollbackFor())
                .contains(SecurityStateService.SecurityStateUnavailableException.class));
    }

    @Test
    void revokeAllCommitsDatabaseBeforeAttemptingEveryRedisTombstone() {
        when(revocationService.revokeAllForUser(eq(42L), any(LocalDateTime.class)))
                .thenReturn(List.of("family-a", "family-b"));
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(securityStateService)
                .revokeFamily(eq(42L), eq("family-a"), any());

        assertThrows(SecurityStateService.SecurityStateUnavailableException.class,
                () -> service.revokeAllForUser(42L));

        var ordered = inOrder(revocationService, securityStateService);
        ordered.verify(revocationService).revokeAllForUser(
                eq(42L), any(LocalDateTime.class));
        ordered.verify(securityStateService).revokeFamily(eq(42L), eq("family-a"), any());
        ordered.verify(securityStateService).revokeFamily(eq(42L), eq("family-b"), any());
    }

    @Test
    void securityChangePublishesNewVersionBeforeRevokingAllUnrevokedFamilies() {
        when(revocationService.revokeAllForUser(eq(42L), any(LocalDateTime.class)))
                .thenReturn(List.of("family-a", "family-b"));

        service.publishSecurityChangeAndRevokeAll(user);

        assertEquals(2L, user.getSecurityVersion());
        var ordered = inOrder(securityStateService);
        ordered.verify(securityStateService).publishAccountState(42L, 2L, true);
        ordered.verify(securityStateService).revokeFamily(eq(42L), eq("family-a"), any());
        ordered.verify(securityStateService).revokeFamily(eq(42L), eq("family-b"), any());
        verify(revocationService).revokeAllForUser(eq(42L), any(LocalDateTime.class));
    }

    @Test
    void disablingAccountPublishesInactiveNewVersion() {
        user.setEnabled(false);

        service.publishSecurityChangeAndRevokeAll(user);

        assertEquals(2L, user.getSecurityVersion());
        verify(securityStateService).publishAccountState(42L, 2L, false);
    }

    @Test
    void databaseRollbackRestoresOnlyTheStatePublishedByTheTransaction() {
        SecurityStateService.AccountSecurityState previousState =
                new SecurityStateService.AccountSecurityState(1L, true);
        when(securityStateService.readAccountState(42L))
                .thenReturn(Optional.of(previousState));
        when(securityStateService.restoreAccountStateIfCurrent(
                42L,
                new SecurityStateService.AccountSecurityState(2L, true),
                Optional.of(previousState)))
                .thenReturn(true);
        when(revocationService.revokeAllForUser(eq(42L), any(LocalDateTime.class)))
                .thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();

        service.publishSecurityChangeAndRevokeAll(user);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(securityStateService).restoreAccountStateIfCurrent(
                42L,
                new SecurityStateService.AccountSecurityState(2L, true),
                Optional.of(previousState));
    }

    @Test
    void committedSecurityChangeDoesNotRestorePreviousState() {
        when(revocationService.revokeAllForUser(eq(42L), any(LocalDateTime.class)))
                .thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();

        service.publishSecurityChangeAndRevokeAll(user);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                TransactionSynchronization.STATUS_COMMITTED));

        verify(securityStateService, never()).restoreAccountStateIfCurrent(
                anyLong(), any(), any());
    }

    private java.time.Duration serviceFamilyLifetime() {
        return java.time.Duration.ofDays(30).plusMillis(300_000L).plusSeconds(60L);
    }

    private AuthSession activeSession(String familyId) {
        return AuthSession.builder()
                .userId(42L)
                .familyId(familyId)
                .securityVersion(1L)
                .tokenHash("current-token-hash")
                .createdAt(LocalDateTime.now().minusDays(1))
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
    }

    private AuthSession persistedNewSession() {
        var captor = org.mockito.ArgumentCaptor.forClass(AuthSession.class);
        verify(sessionRepository).save(captor.capture());
        AuthSession persisted = captor.getValue();
        assertEquals(42L, persisted.getUserId());
        assertEquals(64, persisted.getTokenHash().length());
        return persisted;
    }
}
