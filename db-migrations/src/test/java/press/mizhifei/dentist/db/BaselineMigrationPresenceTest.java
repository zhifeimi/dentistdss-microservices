package press.mizhifei.dentist.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * DATA-02 authoring rule: a table no service role is granted on breaks
     * its owning service at boot (ddl-auto=validate cannot see it). Every
     * table any migration creates must appear in some table-level GRANT.
     */
    @Test
    void everyCreatedTableIsGrantedToAServiceRole() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String migration : new String[] {"V1__baseline.sql", "V2__audit_outbox.sql",
                "V3__service_roles.sql", "V4__clinical_records_sequence_grant.sql",
                "V5__public_clinic_directory.sql" }) {
            try (InputStream in = getClass().getResourceAsStream("/db/migration/" + migration)) {
                assertNotNull(in, migration + " must be packaged under db/migration");
                all.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT)).append('\n');
            }
        }
        String sql = all.toString().replaceAll("--[^\\n]*", "");

        Set<String> granted = new HashSet<>();
        Matcher grant = Pattern.compile("grant\\s+[a-z,\\s]+?\\s+on\\s+(?!sequence\\b)([^;]+?)\\s+to\\s+svc_[a-z_]+\\s*;")
                .matcher(sql);
        while (grant.find()) {
            for (String table : grant.group(1).split(",")) {
                granted.add(table.trim());
            }
        }

        Matcher created = Pattern.compile("create table\\s+([a-z_]+)").matcher(sql);
        int tables = 0;
        while (created.find()) {
            tables++;
            assertTrue(granted.contains(created.group(1)),
                    created.group(1) + " is created but never granted to a service role");
        }
        assertTrue(tables >= 25, "expected the baseline plus later tables, found " + tables);
    }

    @Test
    void publicClinicDirectoryMigrationKeepsExistingClinicsUnlisted() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V5__public_clinic_directory.sql")) {
            assertNotNull(in, "V5__public_clinic_directory.sql must be packaged under db/migration");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Public listing is opt-in: no existing clinic may become public just
        // because the column was added.
        assertTrue(sql.contains("listed_publicly boolean default false not null"),
                "listed_publicly must default to false");
        // Old URLs must never resolve to a different clinic.
        assertTrue(sql.contains("constraint uk_clinics_slug unique (slug)"), "slug must be unique");
        assertTrue(sql.contains("insert into public_directory_sync (id) values (1);"),
                "the rebuild-trigger singleton row must be seeded");
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
