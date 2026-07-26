package press.mizhifei.dentist.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the standalone migrator's configuration contract: defaults point
 * at the in-cluster database and a missing owner password is reported as
 * "not present" (main maps that to exit code 2 before any database
 * connection is attempted).
 */
class MigratorArgsTest {

    @Test
    void defaultsPointAtTheClusterDatabase() {
        assertEquals("jdbc:postgresql://postgres:5432/dentistdss", Migrator.DEFAULT_URL);
        assertEquals("dentistdss", Migrator.DEFAULT_USERNAME);
    }

    @Test
    void missingOwnerPasswordIsReportedAbsent() {
        String password = System.getenv("POSTGRES_PASSWORD");
        assumeTrue(password == null || password.isBlank(),
                "POSTGRES_PASSWORD is set in this environment — cannot exercise the missing case");
        assertNull(Migrator.ownerPassword(),
                "an unset/blank POSTGRES_PASSWORD must read as absent (exit-2 contract)");
    }
}
