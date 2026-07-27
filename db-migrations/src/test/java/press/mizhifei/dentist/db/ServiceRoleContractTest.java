package press.mizhifei.dentist.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * DATA-02 credential contract: proves the per-service PostgreSQL roles
 * created by {@code V3__service_roles.sql} enforce least privilege — each
 * role reads and writes its own tables and is denied cross-service access
 * and DDL. This is the regression evidence for the finding "applications use
 * shared/root database credentials": unauthorized cross-table access fails.
 *
 * <p>
 * The suite migrates a real database as the superuser (V1+V2+V3), then sets
 * throwaway passwords on the seven service roles and connects as each one.
 * Role passwords are set by the test itself; the same password constant is
 * used for every role and never leaves the ephemeral test database.
 *
 * <p>
 * Skipped unless {@code TEST_DATABASE_URL} is set (local Docker or the CI
 * postgres service container).
 */
class ServiceRoleContractTest {

    /** Test-only password for the ephemeral contract database. */
    private static final String ROLE_TEST_PASSWORD = "contract-test-only";

    /** role -> table it MUST be able to read. */
    private static final Map<String, String> OWN_TABLE = new LinkedHashMap<>();
    /** role -> table it MUST NOT be able to read. */
    private static final Map<String, String> FOREIGN_TABLE = new LinkedHashMap<>();

    static {
        OWN_TABLE.put("svc_auth", "users");
        OWN_TABLE.put("svc_clinic", "services");
        OWN_TABLE.put("svc_user_profile", "patients");
        OWN_TABLE.put("svc_appointment", "appointments");
        OWN_TABLE.put("svc_clinical_records", "clinical_notes");
        OWN_TABLE.put("svc_notification", "notifications");
        OWN_TABLE.put("svc_system", "system_settings");

        FOREIGN_TABLE.put("svc_auth", "notifications");
        FOREIGN_TABLE.put("svc_clinic", "users");
        FOREIGN_TABLE.put("svc_user_profile", "appointments");
        FOREIGN_TABLE.put("svc_appointment", "users");
        FOREIGN_TABLE.put("svc_clinical_records", "users");
        FOREIGN_TABLE.put("svc_notification", "users");
        FOREIGN_TABLE.put("svc_system", "users");
    }

    @BeforeAll
    static void migrateAndSetRolePasswords() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null, "TEST_DATABASE_URL not set — skipping service-role contract test");
        String admin = System.getenv("TEST_DATABASE_USERNAME");
        String adminPassword = System.getenv("TEST_DATABASE_PASSWORD");

        Flyway.configure()
                .dataSource(url, admin, adminPassword)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection c = DriverManager.getConnection(url, admin, adminPassword);
                Statement s = c.createStatement()) {
            for (String role : OWN_TABLE.keySet()) {
                s.execute("alter role " + role + " with login password '" + ROLE_TEST_PASSWORD + "'");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to set contract role passwords", e);
        }
    }

    @Test
    void everyRoleReadsItsOwnTable() {
        OWN_TABLE.forEach((role, table) -> assertDoesNotThrow(
                () -> query(role, "select count(*) from " + table),
                role + " must read its own table " + table));
    }

    @Test
    void everyRoleIsDeniedCrossServiceReads() {
        FOREIGN_TABLE.forEach((role, table) -> {
            SQLException e = assertThrows(SQLException.class,
                    () -> query(role, "select count(*) from " + table),
                    role + " must NOT read " + table);
            assertEquals("42501", e.getSQLState(),
                    role + " -> " + table + " must fail with insufficient_privilege");
        });
    }

    @Test
    void sharedTableWritersKeepWriteAccess() {
        // Zero-row INSERTs: the privilege check happens at plan time, so no
        // constraint data is needed and nothing is persisted.
        assertDoesNotThrow(() -> query("svc_auth", "insert into users select * from users where false"),
                "svc_auth must write the shared users table");
        assertDoesNotThrow(() -> query("svc_user_profile", "insert into users select * from users where false"),
                "svc_user_profile must write the shared users table");
        assertDoesNotThrow(() -> query("svc_auth", "insert into clinics select * from clinics where false"),
                "svc_auth must write the shared clinics table");
        assertDoesNotThrow(() -> query("svc_clinic", "insert into clinics select * from clinics where false"),
                "svc_clinic must write the shared clinics table");
        assertDoesNotThrow(() -> query("svc_user_profile",
                "insert into user_approval_requests select * from user_approval_requests where false"),
                "svc_user_profile must write the shared user_approval_requests table");
    }

    @Test
    void nonOwnerCannotWriteSharedTables() {
        SQLException e = assertThrows(SQLException.class,
                () -> query("svc_appointment", "insert into users select * from users where false"));
        assertEquals("42501", e.getSQLState(), "svc_appointment must not insert into users");
    }

    @Test
    void sequenceUsageFollowsTheGrantMatrix() {
        assertDoesNotThrow(() -> query("svc_appointment", "select nextval('appointment_id_seq')"),
                "svc_appointment owns appointment_id_seq");
        SQLException e = assertThrows(SQLException.class,
                () -> query("svc_auth", "select nextval('appointment_id_seq')"));
        assertEquals("42501", e.getSQLState(), "svc_auth must not use appointment_id_seq");
    }

    @Test
    void serviceRolesCannotExecuteDdl() {
        for (String role : OWN_TABLE.keySet()) {
            SQLException e = assertThrows(SQLException.class,
                    () -> query(role, "create table contract_ddl_probe (id int)"),
                    role + " must not execute DDL");
            assertEquals("42501", e.getSQLState(), role + " DDL probe");
        }
    }

    private static void query(String role, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(
                System.getenv("TEST_DATABASE_URL"), role, ROLE_TEST_PASSWORD);
                Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
