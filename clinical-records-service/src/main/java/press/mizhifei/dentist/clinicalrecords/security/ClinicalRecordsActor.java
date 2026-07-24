package press.mizhifei.dentist.clinicalrecords.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import press.mizhifei.dentist.security.AuthenticatedUser;

import java.util.Set;

public record ClinicalRecordsActor(long userId, Set<String> roles, Long clinicId) {

    public static ClinicalRecordsActor from(Jwt jwt) {
        try {
            AuthenticatedUser user = AuthenticatedUser.from(jwt);
            long userId = user.requiredNumericUserId();
            if (userId <= 0) {
                throw new AccessDeniedException("Authenticated identity is unavailable");
            }
            return new ClinicalRecordsActor(userId, user.roles(), user.clinicId());
        } catch (IllegalStateException exception) {
            throw new AccessDeniedException("Authenticated identity is unavailable", exception);
        }
    }

    public boolean isSystemAdmin() {
        return roles.contains("SYSTEM_ADMIN");
    }

    public boolean isPatient() {
        return roles.contains("PATIENT");
    }

    public boolean isDentist() {
        return roles.contains("DENTIST");
    }

    public long requiredClinicId() {
        if (clinicId == null || clinicId <= 0) {
            throw new AccessDeniedException("Clinic-scoped access is unavailable");
        }
        return clinicId;
    }
}
