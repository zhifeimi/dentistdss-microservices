package press.mizhifei.dentist.appointment.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import press.mizhifei.dentist.security.AuthenticatedUser;

import java.util.Set;

public record AppointmentActor(long userId, Set<String> roles, Long clinicId) {

    public static AppointmentActor from(Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return new AppointmentActor(
                user.requiredNumericUserId(),
                user.roles(),
                user.clinicId());
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

    public boolean isClinicAdministrator() {
        return roles.contains("CLINIC_ADMIN");
    }

    public boolean isReceptionist() {
        return roles.contains("RECEPTIONIST");
    }

    public boolean isClinicStaff() {
        return isClinicAdministrator() || isReceptionist();
    }

    public long requiredClinicId() {
        if (clinicId == null || clinicId <= 0) {
            throw new AccessDeniedException("Clinic-scoped access is unavailable");
        }
        return clinicId;
    }
}
