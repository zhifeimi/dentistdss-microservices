package press.mizhifei.dentist.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import press.mizhifei.dentist.auth.dto.AuthResponse;
import press.mizhifei.dentist.auth.dto.LoginRequest;
import press.mizhifei.dentist.auth.dto.SessionTokens;
import press.mizhifei.dentist.auth.audit.AuditEventPublisher;
import press.mizhifei.dentist.auth.model.AuthSession;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.repository.AuthSessionRepository;
import press.mizhifei.dentist.auth.repository.UserRepository;
import press.mizhifei.dentist.auth.security.JwtTokenProvider;
import press.mizhifei.dentist.auth.security.UserPrincipal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSessionService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final AuthSessionRevocationService revocationService;
    private final SecurityStateOutboxService outboxService;
    private final JwtTokenProvider tokenProvider;
    private final SecurityStateService securityStateService;
    private final AuditEventPublisher auditEventPublisher;

    @Value("${app.security.refresh-token-days:30}")
    private long refreshTokenDays;

    @Value("${jwt.expiration}")
    private long accessTokenExpirationMs;

    @Value("${jwt.clock-skew-seconds:60}")
    private long jwtClockSkewSeconds;

    @Transactional
    public SessionTokens authenticate(LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getPassword()));
        User user = userRepository.findByEmailForUpdate(loginRequest.getEmail())
                .filter(this::isActiveAccount)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!(authentication.getPrincipal() instanceof UserPrincipal authenticatedPrincipal)
                || authenticatedPrincipal.getSecurityVersion() != user.getSecurityVersion()) {
            throw new BadCredentialsException("Account changed during authentication");
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        return issueNewFamily(user, authentication);
    }

    @Transactional
    public SessionTokens issueForUser(User user) {
        requireActiveAccount(user);
        UserPrincipal principal = UserPrincipal.create(user);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        return issueNewFamily(user, authentication);
    }

    @Transactional(noRollbackFor = {
            BadCredentialsException.class,
            SecurityStateService.SecurityStateUnavailableException.class
    })
    public SessionTokens refresh(String rawRefreshToken) {
        String tokenHash = hash(rawRefreshToken);
        AuthSession current = sessionRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        LocalDateTime now = LocalDateTime.now();

        if (!current.isActive(now)) {
            revokeFamilyWithinRefresh(current.getUserId(), current.getFamilyId(), now);
            auditEventPublisher.publish(
                    "SESSION_FAMILY_REVOKED",
                    "user:" + current.getUserId(),
                    current.getUserId(),
                    null,
                    Map.of("familyId", current.getFamilyId(),
                            "reason", "refresh-token-reuse"));
            throw new BadCredentialsException("Refresh token reuse or expiry detected");
        }

        SecurityStateService.FamilySecurityState familyState = securityStateService
                .readFamilyState(current.getUserId(), current.getFamilyId())
                .orElseThrow(SecurityStateService.SecurityStateUnavailableException::new);
        if (familyState == SecurityStateService.FamilySecurityState.REVOKED) {
            revokeFamilyWithinRefresh(current.getUserId(), current.getFamilyId(), now);
            throw new BadCredentialsException("Refresh family is revoked");
        }

        User user = userRepository.findById(current.getUserId())
                .orElse(null);
        if (!isActiveAccount(user)
                || current.getSecurityVersion() != user.getSecurityVersion()) {
            revokeFamilyWithinRefresh(current.getUserId(), current.getFamilyId(), now);
            throw new BadCredentialsException("Account is unavailable");
        }
        SecurityStateService.AccountSecurityState accountState = securityStateService
                .readAccountState(user.getId())
                .orElseThrow(SecurityStateService.SecurityStateUnavailableException::new);
        if (!accountState.active() || accountState.securityVersion() != user.getSecurityVersion()) {
            revokeFamilyWithinRefresh(current.getUserId(), current.getFamilyId(), now);
            throw new BadCredentialsException("Account is unavailable");
        }

        String replacement = securityStateService.randomToken(48);
        String replacementHash = hash(replacement);
        int rotated = sessionRepository.rotateIfActive(
                tokenHash,
                now,
                now,
                replacementHash);
        if (rotated != 1) {
            revokeFamilyWithinRefresh(current.getUserId(), current.getFamilyId(), now);
            auditEventPublisher.publish(
                    "SESSION_FAMILY_REVOKED",
                    "user:" + current.getUserId(),
                    current.getUserId(),
                    null,
                    Map.of("familyId", current.getFamilyId(),
                            "reason", "refresh-token-rotation-conflict"));
            throw new BadCredentialsException("Refresh token reuse detected");
        }

        boolean familyExtended;
        try {
            familyExtended = securityStateService.extendActiveFamily(
                    current.getUserId(), current.getFamilyId(), familyLifetime());
        } catch (RuntimeException ex) {
            revokeFamilyAfterExtensionFailure(
                    current.getUserId(), current.getFamilyId(), now, ex);
            throw new SecurityStateService.SecurityStateUnavailableException(ex);
        }
        if (!familyExtended) {
            revokeFamilyWithinRefresh(current.getUserId(), current.getFamilyId(), now);
            throw new BadCredentialsException("Refresh family was revoked");
        }

        UserPrincipal principal = UserPrincipal.create(user);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities());
        persistSession(user.getId(), current.getFamilyId(), user.getSecurityVersion(), replacementHash, now);
        return tokens(user, authentication, current.getFamilyId(), replacement);
    }

    public Optional<RevokedFamily> revoke(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findByTokenHash(hash(rawRefreshToken))
                .map(session -> {
                    revokeFamilyDurably(
                            session.getUserId(),
                            session.getFamilyId(),
                            LocalDateTime.now());
                    return new RevokedFamily(session.getUserId(), session.getFamilyId());
                });
    }

    public void revokeFamily(long userId, String familyId) {
        revokeFamilyDurably(userId, familyId, LocalDateTime.now());
    }

    public void revokeAllForUser(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        var familyIds = revocationService.revokeAllForUser(userId, now);
        Duration tombstoneTtl = familyLifetime();
        for (String familyId : familyIds) {
            outboxService.enqueueStandalone(userId, familyId, now, tombstoneTtl);
        }

        RuntimeException publicationFailure = null;
        for (String familyId : familyIds) {
            try {
                publishFamilyTombstone(userId, familyId);
                outboxService.markPublished(userId, familyId);
            } catch (RuntimeException ex) {
                if (publicationFailure == null) {
                    publicationFailure = ex;
                } else {
                    publicationFailure.addSuppressed(ex);
                }
            }
        }
        if (publicationFailure != null) {
            throw stateUnavailable(publicationFailure);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishSecurityChangeAndRevokeAll(User user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new IllegalArgumentException("Persisted user is required");
        }
        Optional<SecurityStateService.AccountSecurityState> previousState =
                securityStateService.readAccountState(user.getId());
        user.incrementSecurityVersion();
        SecurityStateService.AccountSecurityState publishedState =
                new SecurityStateService.AccountSecurityState(
                        user.getSecurityVersion(),
                        isActiveAccount(user));
        registerRollbackCompensation(user.getId(), publishedState, previousState);
        publishAccountState(user);
        revokeAllForUser(user.getId());
    }

    private SessionTokens issueNewFamily(User user, Authentication authentication) {
        requireActiveAccount(user);
        String familyId = UUID.randomUUID().toString();
        String refreshToken = securityStateService.randomToken(48);
        LocalDateTime now = LocalDateTime.now();
        persistSession(user.getId(), familyId, user.getSecurityVersion(), hash(refreshToken), now);
        publishAccountState(user);
        if (!securityStateService.activateFamily(user.getId(), familyId, familyLifetime())) {
            throw new SecurityStateService.SecurityStateUnavailableException();
        }
        // Single login-issuance point: both password login (authenticate) and
        // federated login (issueForUser) funnel through here exactly once per
        // new session family; refresh rotation deliberately does not.
        auditEventPublisher.publish(
                "LOGIN_SUCCESS",
                "user:" + user.getId(),
                user.getId(),
                user.getClinicId(),
                Map.of("familyId", familyId));
        return tokens(user, authentication, familyId, refreshToken);
    }

    private void persistSession(
            Long userId,
            String familyId,
            long securityVersion,
            String tokenHash,
            LocalDateTime now) {
        sessionRepository.save(AuthSession.builder()
                .userId(userId)
                .familyId(familyId)
                .securityVersion(securityVersion)
                .tokenHash(tokenHash)
                .createdAt(now)
                .expiresAt(now.plusDays(refreshTokenDays))
                .build());
    }

    private SessionTokens tokens(
            User user,
            Authentication authentication,
            String familyId,
            String refreshToken) {
        AuthResponse response = AuthResponse.builder()
                .accessToken(tokenProvider.generateToken(authentication, familyId))
                .tokenType("Bearer")
                .user(user.toUserResponse())
                .build();
        return new SessionTokens(response, refreshToken, securityStateService.randomToken(32));
    }

    private void registerRollbackCompensation(
            long userId,
            SecurityStateService.AccountSecurityState publishedState,
            Optional<SecurityStateService.AccountSecurityState> previousState) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    if (!securityStateService.restoreAccountStateIfCurrent(
                            userId, publishedState, previousState)) {
                        log.warn("Skipped account-state rollback compensation because authoritative state advanced");
                    }
                } catch (RuntimeException ex) {
                    log.error("Failed to compensate rolled-back account security state", ex);
                }
            }
        });
    }

    private void publishAccountState(User user) {
        securityStateService.publishAccountState(
                user.getId(),
                user.getSecurityVersion(),
                isActiveAccount(user));
    }

    private void revokeFamilyDurably(
            long userId,
            String familyId,
            LocalDateTime revokedAt) {
        revocationService.revokeFamily(userId, familyId, revokedAt);
        outboxService.enqueueStandalone(userId, familyId, revokedAt, familyLifetime());
        try {
            publishFamilyTombstone(userId, familyId);
            outboxService.markPublished(userId, familyId);
        } catch (RuntimeException ex) {
            throw stateUnavailable(ex);
        }
    }

    private void revokeFamilyWithinRefresh(
            long userId,
            String familyId,
            LocalDateTime revokedAt) {
        sessionRepository.revokeActiveByUserIdAndFamilyId(userId, familyId, revokedAt);
        outboxService.enqueue(userId, familyId, revokedAt, familyLifetime());
        try {
            publishFamilyTombstone(userId, familyId);
            outboxService.markPublished(userId, familyId);
        } catch (RuntimeException ex) {
            throw stateUnavailable(ex);
        }
    }

    private void revokeFamilyAfterExtensionFailure(
            long userId,
            String familyId,
            LocalDateTime revokedAt,
            RuntimeException extensionFailure) {
        sessionRepository.revokeActiveByUserIdAndFamilyId(userId, familyId, revokedAt);
        outboxService.enqueue(userId, familyId, revokedAt, familyLifetime());
        try {
            publishFamilyTombstone(userId, familyId);
            outboxService.markPublished(userId, familyId);
        } catch (RuntimeException tombstoneFailure) {
            extensionFailure.addSuppressed(tombstoneFailure);
        }
    }

    private void publishFamilyTombstone(long userId, String familyId) {
        securityStateService.revokeFamily(userId, familyId, familyLifetime());
    }

    private SecurityStateService.SecurityStateUnavailableException stateUnavailable(
            RuntimeException cause) {
        if (cause instanceof SecurityStateService.SecurityStateUnavailableException unavailable) {
            return unavailable;
        }
        return new SecurityStateService.SecurityStateUnavailableException(cause);
    }

    private Duration familyLifetime() {
        return Duration.ofDays(refreshTokenDays)
                .plusMillis(accessTokenExpirationMs)
                .plusSeconds(jwtClockSkewSeconds);
    }

    private void requireActiveAccount(User user) {
        if (!isActiveAccount(user)) {
            throw new BadCredentialsException("Account is unavailable");
        }
    }

    private boolean isActiveAccount(User user) {
        return user != null
                && user.isEnabled()
                && user.isAccountNonExpired()
                && user.isCredentialsNonExpired()
                && user.isAccountNonLocked();
    }

    public record RevokedFamily(long userId, String familyId) {
    }

    private String hash(String value) {
        if (value == null || value.isBlank()) {
            throw new BadCredentialsException("Missing refresh token");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash refresh token", ex);
        }
    }
}
