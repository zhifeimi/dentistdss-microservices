package press.mizhifei.dentist.clinicalrecords;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import press.mizhifei.dentist.db.SchemaContract;

/**
 * Opt-in schema contract: migrates a real PostgreSQL database with the Flyway
 * baseline bundled in the db-migrations module and validates the
 * clinical-records-service entity mappings (clinical_notes with text[]
 * attachments, dental_images, service_visits, treatment_plans,
 * treatment_plan_items) against it.
 *
 * <p>
 * Skipped unless {@code TEST_DATABASE_URL} is set (local Docker or the CI
 * postgres service container).
 */
class ClinicalRecordsSchemaContractTest {

    @Test
    void schemaValidatesAgainstMigratedDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null, "TEST_DATABASE_URL not set — skipping schema contract test");
        SchemaContract.migrateAndValidate(url,
                System.getenv("TEST_DATABASE_USERNAME"),
                System.getenv("TEST_DATABASE_PASSWORD"),
                "press.mizhifei.dentist.clinicalrecords.model");
    }
}
