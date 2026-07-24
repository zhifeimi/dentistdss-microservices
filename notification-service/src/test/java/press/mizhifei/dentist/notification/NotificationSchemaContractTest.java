package press.mizhifei.dentist.notification;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import press.mizhifei.dentist.db.SchemaContract;

/**
 * Opt-in schema contract: migrates a real PostgreSQL database with the Flyway
 * baseline bundled in the db-migrations module and validates the
 * notification-service entity mappings (notifications with the native
 * notification_type/notification_status enums and the jsonb metadata column,
 * notification_templates with text[] variables) against it.
 *
 * <p>
 * Skipped unless {@code TEST_DATABASE_URL} is set (local Docker or the CI
 * postgres service container).
 */
class NotificationSchemaContractTest {

    @Test
    void schemaValidatesAgainstMigratedDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null, "TEST_DATABASE_URL not set — skipping schema contract test");
        SchemaContract.migrateAndValidate(url,
                System.getenv("TEST_DATABASE_USERNAME"),
                System.getenv("TEST_DATABASE_PASSWORD"),
                "press.mizhifei.dentist.notification.model");
    }
}
