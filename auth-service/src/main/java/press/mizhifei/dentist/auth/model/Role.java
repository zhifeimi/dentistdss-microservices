package press.mizhifei.dentist.auth.model;

import java.util.Locale;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */

public enum Role {
    SYSTEM_ADMIN,
    CLINIC_ADMIN, // Dental Clinic Administrator
    DENTIST,
    RECEPTIONIST,
    PATIENT;

    public static Role fromString(String role) {
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        return Role.valueOf(role.toUpperCase(Locale.ROOT));
    }
}
