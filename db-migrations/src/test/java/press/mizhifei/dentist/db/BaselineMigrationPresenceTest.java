package press.mizhifei.dentist.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Guards the baseline migration resource that every JDBC service depends on.
 * Flyway's default behavior is to silently apply nothing when a migration
 * location is missing; a packaging regression in this module would otherwise
 * surface only at service boot (and would be masked by baseline-on-migrate on
 * existing databases). These checks need no database and run in every build.
 */
class BaselineMigrationPresenceTest {

    @Test
    void baselineMigrationIsPackagedWithExpectedStructure() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V1__baseline.sql")) {
            assertNotNull(in, "V1__baseline.sql must be packaged under db/migration");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // The four native enum types ddl-auto=update cannot create.
        assertTrue(sql.contains("create type appointment_status"), "appointment_status type missing");
        assertTrue(sql.contains("create type urgency_level"), "urgency_level type missing");
        assertTrue(sql.contains("create type notification_type"), "notification_type type missing");
        assertTrue(sql.contains("create type notification_status"), "notification_status type missing");

        // Types must exist before the tables that reference them.
        assertTrue(sql.indexOf("create type") < sql.indexOf("create table"),
                "enum types must be created before tables");

        // The frozen baseline: 21 tables and 4 standalone sequences.
        // clinics.id is a plain identity column (no named sequence).
        assertEquals(21, countOccurrences(sql, "create table "), "baseline table count changed");
        assertEquals(4, countOccurrences(sql, "create sequence "), "baseline sequence count changed");

        // Hibernate 7 hard-fails validation when a sequence increment differs
        // from allocationSize (1 everywhere); keep every sequence at increment 1.
        assertTrue(countOccurrences(sql, "increment by 1") >= 4,
                "every standalone sequence must use increment by 1");

        // No identity column may name its backing sequence: Hibernate's
        // PostgreSQL metadata extraction cannot see identity-owned sequences,
        // so a named clinic_id_seq would fail ddl-auto=validate at boot.
        assertFalse(sql.contains("sequence name"), "identity columns must not name their sequences");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    void serviceRolesMigrationIsPackagedWithExpectedStructure() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V3__service_roles.sql")) {
            assertNotNull(in, "V3__service_roles.sql must be packaged under db/migration");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // All seven per-service roles, each created and granted.
        for (String role : new String[] {"svc_auth", "svc_clinic", "svc_user_profile", "svc_appointment",
                "svc_clinical_records", "svc_notification", "svc_system" }) {
            assertTrue(sql.contains("create role " + role + " login"), role + " role creation missing");
            assertTrue(sql.contains(" to " + role + ";"), "no grants for " + role);
        }

        // Passwords are set out-of-band by the provision script; a migration
        // must never carry a credential.
        assertFalse(sql.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\b(identified by|with login password)\\b.*"),
                "role passwords must never appear in SQL");

        // The shared-table and collection/join-table grants that are easy to
        // forget: users+user_roles (auth & user-profile), clinics+clinic_admin
        // (auth), user_approval_requests (auth & user-profile).
        for (String table : new String[] {"user_roles", "clinic_admin", "user_approval_requests" }) {
            assertTrue(sql.contains(table), table + " grants missing");
        }
        // Sequence grants follow the inserting roles.
        assertTrue(sql.contains("user_id_seq"), "user_id_seq grants missing");
        assertTrue(sql.contains("appointment_id_seq"), "appointment_id_seq grants missing");
        assertTrue(sql.contains("patient_record_id_seq"), "patient_record_id_seq grants missing");
        assertTrue(sql.contains("notification_id_seq"), "notification_id_seq grants missing");
    }
}
