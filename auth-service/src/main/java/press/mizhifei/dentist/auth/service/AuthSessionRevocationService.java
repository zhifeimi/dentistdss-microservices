package press.mizhifei.dentist.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.auth.repository.AuthSessionRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthSessionRevocationService {

    private final AuthSessionRepository sessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(long userId, String familyId, LocalDateTime revokedAt) {
        return sessionRepository.revokeActiveByUserIdAndFamilyId(
                userId,
                familyId,
                revokedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> revokeAllForUser(long userId, LocalDateTime revokedAt) {
        sessionRepository.revokeActiveByUserId(userId, revokedAt);
        return sessionRepository.findDistinctUnexpiredFamilyIdsByUserId(
                userId,
                revokedAt);
    }
}
