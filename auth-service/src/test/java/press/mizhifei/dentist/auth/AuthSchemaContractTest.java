package press.mizhifei.dentist.auth;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import press.mizhifei.dentist.db.SchemaContract;

/**
 * Opt-in schema contract: migrates a real PostgreSQL database with the Flyway
 * baseline bundled in the db-migrations module and validates the auth-service
 * entity mappings against it. The auth-service model package owns the superset
 * mappings of the shared tables (users, user_roles, clinics, clinic_admin,
 * user_approval_requests) plus auth_sessions and auth_security_outbox.
 *
 * <p>
 * Skipped unless {@code TEST_DATABASE_URL} is set (local Docker or the CI
 * postgres service container).
 */
class AuthSchemaContractTest {

    @Test
    void schemaValidatesAgainstMigratedDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null, "TEST_DATABASE_URL not set — skipping schema contract test");
        SchemaContract.migrateAndValidate(url,
                System.getenv("TEST_DATABASE_USERNAME"),
                System.getenv("TEST_DATABASE_PASSWORD"),
                "press.mizhifei.dentist.auth.model");
    }
}
