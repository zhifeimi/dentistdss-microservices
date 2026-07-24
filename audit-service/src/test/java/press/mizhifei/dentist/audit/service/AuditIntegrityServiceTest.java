package press.mizhifei.dentist.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import press.mizhifei.dentist.audit.dto.IntegrityReport;
import press.mizhifei.dentist.audit.model.AuditEntry;
import press.mizhifei.dentist.audit.model.AuditSeal;
import press.mizhifei.dentist.audit.repository.AuditEntryRepository;
import press.mizhifei.dentist.audit.repository.AuditSealRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the audit chain verifier (AUDIT-01), built on a fixture
 * chain of two seals over two content-hashed documents each, with all seal
 * math computed through the real sealer functions and the real content
 * hasher. Each tamper shape must surface as its dedicated issue type.
 */
class AuditIntegrityServiceTest {

    private static final String ID_A1 = "665f00000000000000000010";
    private static final String ID_A2 = "665f00000000000000000020";
    private static final String ID_B1 = "665f00000000000000000030";
    private static final String ID_INS = "665f00000000000000000038";
    private static final String ID_B2 = "665f00000000000000000040";

    private AuditSealRepository sealRepository;
    private AuditEntryRepository entryRepository;
    private AuditContentHasher contentHasher;
    private AuditIntegrityService service;

    @BeforeEach
    void setUp() {
        sealRepository = mock(AuditSealRepository.class);
        entryRepository = mock(AuditEntryRepository.class);
        contentHasher = new AuditContentHasher();
        service = new AuditIntegrityService(sealRepository, entryRepository, contentHasher);
    }

    private record Fixture(AuditEntry a1, AuditEntry a2, AuditEntry b1, AuditEntry b2,
            AuditSeal s1, AuditSeal s2) {
    }

    private Fixture wireValidChain() {
        AuditEntry a1 = doc(ID_A1, "LOGIN_SUCCESS");
        AuditEntry a2 = doc(ID_A2, "LOGOUT");
        AuditEntry b1 = doc(ID_B1, "LOGIN_SUCCESS");
        AuditEntry b2 = doc(ID_B2, "LOGOUT");
        AuditSeal s1 = seal(1L, AuditSealingService.GENESIS, List.of(a1, a2));
        AuditSeal s2 = seal(2L, s1.getSealHash(), List.of(b1, b2));

        when(sealRepository.findAllByOrderBySequenceAsc()).thenReturn(List.of(s1, s2));
        when(entryRepository.findByIdGreaterThanEqualAndIdLessThanEqualOrderByIdAsc(ID_A1, ID_A2))
                .thenReturn(List.of(a1, a2));
        when(entryRepository.findByIdGreaterThanEqualAndIdLessThanEqualOrderByIdAsc(ID_B1, ID_B2))
                .thenReturn(List.of(b1, b2));
        return new Fixture(a1, a2, b1, b2, s1, s2);
    }

    @Test
    void validChainVerifiesClean() {
        wireValidChain();
        when(entryRepository.countByContentHashNotNullAndIdGreaterThan(ID_B2)).thenReturn(3L);

        IntegrityReport report = service.verify();

        assertTrue(report.isVerified());
        assertEquals(2L, report.getSealsChecked());
        assertEquals(4L, report.getDocumentsChecked());
        assertEquals(3L, report.getUnsealedDocuments());
        assertNull(report.getFirstIssue());
    }

    @Test
    void emptyChainVerifiesWithUnsealedBacklog() {
        when(sealRepository.findAllByOrderBySequenceAsc()).thenReturn(List.of());
        when(entryRepository.countByContentHashNotNullAndIdGreaterThan(
                AuditSealingService.FIRST_OBJECT_ID)).thenReturn(7L);

        IntegrityReport report = service.verify();

        assertTrue(report.isVerified());
        assertEquals(0L, report.getSealsChecked());
        assertEquals(0L, report.getDocumentsChecked());
        assertEquals(7L, report.getUnsealedDocuments());
    }

    @Test
    void editedDocumentIsDetected() {
        Fixture fx = wireValidChain();
        fx.a2().setAction("TAMPERED_ACTION");

        IntegrityReport report = service.verify();

        assertIssue(report, "DOCUMENT_MODIFIED", 1L);
    }

    @Test
    void missingDocumentHashIsDetected() {
        Fixture fx = wireValidChain();
        fx.a2().setContentHash(null);

        IntegrityReport report = service.verify();

        assertIssue(report, "DOCUMENT_HASH_MISSING", 1L);
    }

    @Test
    void deletedDocumentIsDetected() {
        Fixture fx = wireValidChain();
        when(entryRepository.findByIdGreaterThanEqualAndIdLessThanEqualOrderByIdAsc(ID_B1, ID_B2))
                .thenReturn(List.of(fx.b1()));

        IntegrityReport report = service.verify();

        assertIssue(report, "DOC_COUNT_MISMATCH", 2L);
    }

    @Test
    void insertedDocumentIsDetected() {
        Fixture fx = wireValidChain();
        AuditEntry inserted = doc(ID_INS, "READ");
        when(entryRepository.findByIdGreaterThanEqualAndIdLessThanEqualOrderByIdAsc(ID_B1, ID_B2))
                .thenReturn(List.of(fx.b1(), inserted, fx.b2()));

        IntegrityReport report = service.verify();

        assertIssue(report, "DOC_COUNT_MISMATCH", 2L);
    }

    @Test
    void chainSurgeryIsDetected() {
        Fixture fx = wireValidChain();
        fx.s2().setPreviousSealHash("attacker-controlled-hash");

        IntegrityReport report = service.verify();

        assertIssue(report, "CHAIN_BROKEN", 2L);
    }

    @Test
    void editedSealFieldIsDetected() {
        Fixture fx = wireValidChain();
        fx.s1().setSealHash(AuditHashes.sha256Hex("forged"));

        IntegrityReport report = service.verify();

        assertIssue(report, "SEAL_HASH_MISMATCH", 1L);
    }

    @Test
    void sealDeletionIsDetectedAsSequenceGap() {
        Fixture fx = wireValidChain();
        fx.s2().setSequence(3L);

        IntegrityReport report = service.verify();

        assertIssue(report, "SEQUENCE_GAP", 3L);
    }

    @Test
    void doubleSealingIsDetectedAsDuplicateSequence() {
        Fixture fx = wireValidChain();
        fx.s2().setSequence(1L);

        IntegrityReport report = service.verify();

        assertIssue(report, "DUPLICATE_SEQUENCE", 1L);
    }

    @Test
    void overlappingResealIsDetected() {
        Fixture fx = wireValidChain();
        fx.s2().setFirstId(ID_A2);

        IntegrityReport report = service.verify();

        assertIssue(report, "RANGE_OVERLAP", 2L);
    }

    @Test
    void hashRefreshedEditIsDetectedByTheBatchRoot() {
        Fixture fx = wireValidChain();
        // Field edit with a refreshed contentHash: per-document verification
        // passes, but the stored hashes no longer reproduce the seal's root.
        fx.b1().setAction("TAMPERED_ACTION");
        fx.b1().setContentHash(contentHasher.hash(fx.b1()));

        IntegrityReport report = service.verify();

        assertIssue(report, "BATCH_ROOT_MISMATCH", 2L);
    }

    private static void assertIssue(IntegrityReport report, String type, long sealSequence) {
        assertFalse(report.isVerified());
        assertEquals(type, report.getFirstIssue().getType());
        assertEquals(sealSequence, report.getFirstIssue().getSealSequence());
    }

    private AuditEntry doc(String id, String action) {
        AuditEntry entry = AuditEntry.builder()
                .id(id)
                .actor("auth-service")
                .action(action)
                .target("target-" + id)
                .assertedUserId(42L)
                .assertedClinicId(9L)
                .timestamp(LocalDateTime.parse("2026-07-24T10:00:00"))
                .context(Map.of("familyId", "family-1"))
                .build();
        entry.setContentHash(contentHasher.hash(entry));
        return entry;
    }

    private static AuditSeal seal(long sequence, String previousSealHash, List<AuditEntry> docs) {
        AuditSeal seal = AuditSeal.builder()
                .sequence(sequence)
                .firstId(docs.getFirst().getId())
                .lastId(docs.getLast().getId())
                .count(docs.size())
                .batchRoot(AuditSealingService.computeBatchRoot(docs))
                .previousSealHash(previousSealHash)
                .build();
        seal.setSealHash(AuditSealingService.computeSealHash(seal));
        return seal;
    }
}
