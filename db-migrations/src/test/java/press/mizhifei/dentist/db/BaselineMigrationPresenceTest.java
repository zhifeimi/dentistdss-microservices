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
}
