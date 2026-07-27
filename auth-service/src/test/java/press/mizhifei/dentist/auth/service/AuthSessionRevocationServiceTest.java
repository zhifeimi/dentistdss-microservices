package press.mizhifei.dentist.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.auth.repository.AuthSessionRepository;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionRevocationServiceTest {

    private AuthSessionRepository sessionRepository;
    private AuthSessionRevocationService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AuthSessionRepository.class);
        service = new AuthSessionRevocationService(sessionRepository);
    }

    @Test
    void familyRevocationUsesIndependentTransactionAndDelegatesDatabaseUpdate()
            throws Exception {
        LocalDateTime revokedAt = LocalDateTime.now();
        when(sessionRepository.revokeActiveByUserIdAndFamilyId(
                42L, "family-1", revokedAt)).thenReturn(2);

        assertEquals(2, service.revokeFamily(42L, "family-1", revokedAt));

        verify(sessionRepository).revokeActiveByUserIdAndFamilyId(
                42L, "family-1", revokedAt);
        Transactional transactional = AuthSessionRevocationService.class
                .getMethod("revokeFamily", long.class, String.class, LocalDateTime.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    @Test
    void allFamilyRevocationUsesIndependentTransactionAndDelegatesDatabaseUpdate()
            throws Exception {
        LocalDateTime revokedAt = LocalDateTime.now();
        when(sessionRepository.revokeActiveByUserId(42L, revokedAt)).thenReturn(3);
        when(sessionRepository.findDistinctUnexpiredFamilyIdsByUserId(42L, revokedAt))
                .thenReturn(java.util.List.of("family-a", "family-b"));

        assertEquals(
                java.util.List.of("family-a", "family-b"),
                service.revokeAllForUser(42L, revokedAt));

        verify(sessionRepository).revokeActiveByUserId(42L, revokedAt);
        verify(sessionRepository).findDistinctUnexpiredFamilyIdsByUserId(42L, revokedAt);
        Transactional transactional = AuthSessionRevocationService.class
                .getMethod("revokeAllForUser", long.class, LocalDateTime.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }
}
