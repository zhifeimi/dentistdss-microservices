package press.mizhifei.dentist.audit.service;

import org.junit.jupiter.api.Test;
import press.mizhifei.dentist.audit.model.AuditEntry;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the canonical audit content hash (AUDIT-01). The hash must
 * be deterministic across processes and recomputable from the stored fields
 * alone: identical content hashes identically, every hashed field change
 * flips it, context key order never matters (deep-sorted), arrays keep
 * element order, and the all-null document renders a fixed golden form.
 */
class AuditContentHasherTest {

    private static final LocalDateTime TIMESTAMP = LocalDateTime.parse("2026-07-24T10:15:30");

    private final AuditContentHasher hasher = new AuditContentHasher();

    private AuditEntry base() {
        return AuditEntry.builder()
                .id("665f00000000000000000010")
                .actor("auth-service")
                .action("LOGIN_SUCCESS")
                .target("user:42")
                .assertedUserId(42L)
                .assertedClinicId(9L)
                .timestamp(TIMESTAMP)
                .context(Map.of("familyId", "family-1"))
                .build();
    }

    @Test
    void hashIsDeterministicForIdenticalContent() {
        assertEquals(hasher.hash(base()), hasher.hash(base()));
    }

    @Test
    void hashIsLowercaseHexSha256() {
        assertTrue(hasher.hash(base()).matches("[0-9a-f]{64}"));
    }

    @Test
    void everyHashedFieldChangeFlipsTheHash() {
        AuditEntry entry = base();
        String baseline = hasher.hash(entry);

        entry.setAction("LOGOUT");
        assertNotEquals(baseline, hasher.hash(entry));
        entry.setAction("LOGIN_SUCCESS");

        entry.setActor("notification-service");
        assertNotEquals(baseline, hasher.hash(entry));
        entry.setActor("auth-service");

        entry.setTarget("user:43");
        assertNotEquals(baseline, hasher.hash(entry));
        entry.setTarget("user:42");

        entry.setAssertedUserId(43L);
        assertNotEquals(baseline, hasher.hash(entry));
        entry.setAssertedUserId(42L);

        entry.setAssertedClinicId(10L);
        assertNotEquals(baseline, hasher.hash(entry));
        entry.setAssertedClinicId(9L);

        entry.setTimestamp(TIMESTAMP.plusSeconds(1));
        assertNotEquals(baseline, hasher.hash(entry));
        entry.setTimestamp(TIMESTAMP);

        entry.setContext(Map.of("familyId", "other"));
        assertNotEquals(baseline, hasher.hash(entry));
    }

    @Test
    void documentIdIsNotPartOfTheContentHash() {
        AuditEntry entry = base();
        String baseline = hasher.hash(entry);
        entry.setId("665f00000000000000000099");
        assertEquals(baseline, hasher.hash(entry));
    }

    @Test
    void contextKeyInsertionOrderNeverAffectsTheHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("alpha", 1);
        first.put("beta", 2);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("beta", 2);
        second.put("alpha", 1);

        AuditEntry left = base();
        left.setContext(first);
        AuditEntry right = base();
        right.setContext(second);

        assertEquals(hasher.canonicalize(left), hasher.canonicalize(right));
        assertEquals(hasher.hash(left), hasher.hash(right));
    }

    @Test
    void nestedContextMapsAreDeepSorted() {
        Map<String, Object> innerLeft = new LinkedHashMap<>();
        innerLeft.put("b", 2);
        innerLeft.put("a", 1);
        Map<String, Object> innerRight = new LinkedHashMap<>();
        innerRight.put("a", 1);
        innerRight.put("b", 2);

        AuditEntry left = base();
        left.setContext(Map.of("outer", innerLeft));
        AuditEntry right = base();
        right.setContext(Map.of("outer", innerRight));

        assertEquals(hasher.hash(left), hasher.hash(right));
    }

    @Test
    void arrayElementOrderIsSignificant() {
        AuditEntry left = base();
        left.setContext(Map.of("roles", List.of("DENTIST", "PATIENT")));
        AuditEntry right = base();
        right.setContext(Map.of("roles", List.of("PATIENT", "DENTIST")));

        assertNotEquals(hasher.hash(left), hasher.hash(right));
    }

    @Test
    void allNullFieldsCanonicalizeToTheGoldenForm() {
        AuditEntry empty = AuditEntry.builder().build();

        assertEquals(
                "{\"action\":null,\"actor\":null,\"assertedClinicId\":null,"
                        + "\"assertedUserId\":null,\"context\":null,\"target\":null,"
                        + "\"timestamp\":null}",
                hasher.canonicalize(empty));
    }
}
