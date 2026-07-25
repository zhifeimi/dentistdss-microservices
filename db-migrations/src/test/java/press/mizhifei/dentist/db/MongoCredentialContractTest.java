package press.mizhifei.dentist.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import com.mongodb.MongoSecurityException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

/**
 * DATA-02 credential contract for MongoDB: proves the per-service users
 * provisioned by {@code deploy/scripts/provision-db-roles.sh} enforce the
 * collection-level split — most importantly that the GenAI service can no
 * longer read or write the audit store (audit_entries / audit_seals), which
 * is the compensating control for the documented audit tail-truncation
 * limitation.
 *
 * <p>
 * The suite assumes the roles/users already exist (CI creates them with the
 * real provision script against the mongo service container — the same code
 * path an operator runs) and asserts the effective permissions through the
 * Java driver using throwaway contract passwords.
 *
 * <p>
 * Skipped unless {@code TEST_MONGO_ADMIN_URI} is set. Per-service passwords
 * come from {@code AUDIT_SERVICE_MONGO_PASSWORD},
 * {@code GENAI_SERVICE_MONGO_PASSWORD}, and
 * {@code CLINICAL_RECORDS_SERVICE_MONGO_PASSWORD} (CI sets all three to the
 * same test-only value).
 */
class MongoCredentialContractTest {

    @Test
    void auditUserReadsAndWritesOnlyTheAuditStore() {
        String password = System.getenv("AUDIT_SERVICE_MONGO_PASSWORD");
        assumeTrue(password != null, "AUDIT_SERVICE_MONGO_PASSWORD not set — skipping");
        try (MongoClient client = client("svc_audit", password)) {
            assertDoesNotThrow(() -> client.getDatabase("dentistdss")
                    .getCollection("audit_entries").find().first(),
                    "svc_audit must read audit_entries");
            assertDoesNotThrow(() -> {
                client.getDatabase("dentistdss").getCollection("audit_entries")
                        .insertOne(new Document("contract", "probe"));
                client.getDatabase("dentistdss").getCollection("audit_entries")
                        .deleteOne(new Document("contract", "probe"));
            }, "svc_audit must insert/remove in audit_entries");
            assertDenied(() -> client.getDatabase("dentistdss")
                    .getCollection("conversations").find().first(),
                    "svc_audit must NOT read genai conversations");
            assertDenied(() -> client.getDatabase("dentistdss_files")
                    .getCollection("fs.files").find().first(),
                    "svc_audit must NOT read dentistdss_files");
        }
    }

    @Test
    void genaiUserCannotTouchTheAuditStore() {
        String password = System.getenv("GENAI_SERVICE_MONGO_PASSWORD");
        assumeTrue(password != null, "GENAI_SERVICE_MONGO_PASSWORD not set — skipping");
        try (MongoClient client = client("svc_genai", password)) {
            assertDoesNotThrow(() -> client.getDatabase("dentistdss")
                    .getCollection("conversations").find().first(),
                    "svc_genai must read conversations");
            assertDenied(() -> client.getDatabase("dentistdss")
                    .getCollection("audit_entries").find().first(),
                    "svc_genai must NOT read audit_entries");
            assertDenied(() -> client.getDatabase("dentistdss")
                    .getCollection("audit_seals").find().first(),
                    "svc_genai must NOT read audit_seals");
            assertDenied(() -> client.getDatabase("dentistdss")
                    .getCollection("audit_seals").insertOne(new Document("contract", "probe")),
                    "svc_genai must NOT write audit_seals (tail-truncation control)");
        }
    }

    @Test
    void clinicalRecordsUserIsConfinedToTheFilesDatabase() {
        String password = System.getenv("CLINICAL_RECORDS_SERVICE_MONGO_PASSWORD");
        assumeTrue(password != null, "CLINICAL_RECORDS_SERVICE_MONGO_PASSWORD not set — skipping");
        try (MongoClient client = client("svc_clinical_records", password)) {
            assertDoesNotThrow(() -> client.getDatabase("dentistdss_files")
                    .getCollection("fs.files").find().first(),
                    "svc_clinical_records must read dentistdss_files");
            assertDenied(() -> client.getDatabase("dentistdss")
                    .getCollection("audit_entries").find().first(),
                    "svc_clinical_records must NOT read the audit store");
            assertDenied(() -> client.getDatabase("dentistdss")
                    .getCollection("conversations").find().first(),
                    "svc_clinical_records must NOT read genai conversations");
        }
    }

    private static MongoClient client(String user, String password) {
        String adminUri = System.getenv("TEST_MONGO_ADMIN_URI");
        assumeTrue(adminUri != null, "TEST_MONGO_ADMIN_URI not set — skipping mongo contract test");
        // Swap the admin credentials in the URI for the service user's.
        String uri = adminUri.replaceFirst(
                "mongodb://[^@]+@", "mongodb://" + user + ":" + password + "@");
        return MongoClients.create(uri);
    }

    private static void assertDenied(ThrowingMongoCall call, String message) {
        Exception e = assertThrows(Exception.class, call::run, message);
        assertTrue(e instanceof MongoSecurityException
                || (e.getMessage() != null && e.getMessage().contains("not authorized")),
                message + " — expected an authorization failure, got: " + e);
    }

    @FunctionalInterface
    private interface ThrowingMongoCall {
        void run();
    }
}
