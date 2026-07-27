package press.mizhifei.dentist.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import press.mizhifei.dentist.audit.dto.IntegrityReport;
import press.mizhifei.dentist.audit.model.AuditEntry;
import press.mizhifei.dentist.audit.model.AuditSeal;
import press.mizhifei.dentist.audit.repository.AuditEntryRepository;
import press.mizhifei.dentist.audit.repository.AuditSealRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Re-verifies the tamper-evident audit chain (AUDIT-01) by recomputation —
 * the read-side twin of {@link AuditSealingService}. Walks the seals in
 * sequence order and fails fast at the first inconsistency, layering checks
 * so each tamper shape has a dedicated detection:
 *
 * <ol>
 *   <li>sequence continuity (seal deletion, double-sealing) —
 *       {@code SEQUENCE_GAP} / {@code DUPLICATE_SEQUENCE};</li>
 *   <li>range continuity (overlapping re-seals) — {@code RANGE_OVERLAP};</li>
 *   <li>chain linkage (chain surgery) — {@code CHAIN_BROKEN};</li>
 *   <li>seal self-hash (seal field edits) — {@code SEAL_HASH_MISMATCH};</li>
 *   <li>sealed range vs stored documents (deletion/insertion) —
 *       {@code DOC_COUNT_MISMATCH};</li>
 *   <li>per-document content hash (field edits) —
 *       {@code DOCUMENT_HASH_MISSING} / {@code DOCUMENT_MODIFIED};</li>
 *   <li>batch root over stored hashes (edits that refreshed contentHash
 *       without rebuilding the chain) — {@code BATCH_ROOT_MISMATCH}.</li>
 * </ol>
 *
 * <p>Known limit (security review 2026-07-25, accepted by contract
 * decision — no code change): these checks verify only the surviving
 * chain. Tail truncation — deleting the newest seal(s) together with the
 * entries in their covered {@code _id} ranges — and deletion of every seal
 * both leave a self-consistent state that verifies clean; the deleted
 * documents surface in neither the chain nor the {@code unsealedDocuments}
 * backlog (which counts only entries still present), and
 * {@link AuditSealingService} silently resumes sealing on a truncated
 * chain. Detecting those shapes would require a monotonic high-water mark
 * in a trust domain outside the audit store; until then the practical
 * mitigation is shrinking the audit-store write surface (DATA-02
 * per-service credentials).
 *
 * <p>Detection, not prevention: a fully consistent rewrite of sealed
 * history would require controlling the sealer, and tail truncation or a
 * full chain wipe requires only write access to the audit store. Entries
 * written before this feature carry no content hash and are never sealed;
 * they are invisible to verification by design. Plain method, no
 * transaction — standalone Mongo as deployed has no multi-document
 * transactions.
 */
@Service
@RequiredArgsConstructor
public class AuditIntegrityService {

    private final AuditSealRepository sealRepository;
    private final AuditEntryRepository entryRepository;
    private final AuditContentHasher contentHasher;

    public IntegrityReport verify() {
        List<AuditSeal> seals = sealRepository.findAllByOrderBySequenceAsc();
        if (seals.isEmpty()) {
            return IntegrityReport.builder()
                    .verified(true)
                    .sealsChecked(0)
                    .documentsChecked(0)
                    .unsealedDocuments(countUnsealed(AuditSealingService.FIRST_OBJECT_ID))
                    .build();
        }

        long expectedSequence = 1;
        String previousLastId = null;
        String expectedPreviousHash = AuditSealingService.GENESIS;
        long documentsChecked = 0;

        for (AuditSeal seal : seals) {
            if (seal.getSequence() != expectedSequence) {
                String type = seal.getSequence() < expectedSequence ? "DUPLICATE_SEQUENCE" : "SEQUENCE_GAP";
                return issue(type, seal, documentsChecked,
                        "expected sequence " + expectedSequence + " but found " + seal.getSequence());
            }
            if (previousLastId != null && seal.getFirstId().compareTo(previousLastId) <= 0) {
                return issue("RANGE_OVERLAP", seal, documentsChecked,
                        "firstId " + seal.getFirstId() + " does not continue after " + previousLastId);
            }
            if (!hashEquals(expectedPreviousHash, seal.getPreviousSealHash())) {
                return issue("CHAIN_BROKEN", seal, documentsChecked,
                        "previousSealHash does not match the preceding seal's hash");
            }
            if (!hashEquals(AuditSealingService.computeSealHash(seal), seal.getSealHash())) {
                return issue("SEAL_HASH_MISMATCH", seal, documentsChecked,
                        "stored sealHash does not match the recomputed seal hash");
            }

            List<AuditEntry> docs = entryRepository
                    .findByIdGreaterThanEqualAndIdLessThanEqualOrderByIdAsc(seal.getFirstId(), seal.getLastId());
            if (docs.isEmpty()
                    || docs.size() != seal.getCount()
                    || !docs.getFirst().getId().equals(seal.getFirstId())
                    || !docs.getLast().getId().equals(seal.getLastId())) {
                return issue("DOC_COUNT_MISMATCH", seal, documentsChecked,
                        "sealed range holds " + docs.size() + " documents, seal records " + seal.getCount());
            }
            for (AuditEntry doc : docs) {
                if (doc.getContentHash() == null) {
                    return issue("DOCUMENT_HASH_MISSING", seal, documentsChecked,
                            "document " + doc.getId() + " inside a sealed range has no contentHash");
                }
                if (!hashEquals(contentHasher.hash(doc), doc.getContentHash())) {
                    return issue("DOCUMENT_MODIFIED", seal, documentsChecked,
                            "document " + doc.getId() + " no longer matches its contentHash");
                }
                documentsChecked++;
            }
            if (!hashEquals(AuditSealingService.computeBatchRoot(docs), seal.getBatchRoot())) {
                return issue("BATCH_ROOT_MISMATCH", seal, documentsChecked,
                        "stored content hashes no longer reproduce the seal's batchRoot");
            }

            previousLastId = seal.getLastId();
            expectedPreviousHash = seal.getSealHash();
            expectedSequence++;
        }

        long unsealed = countUnsealed(seals.getLast().getLastId());
        return IntegrityReport.builder()
                .verified(true)
                .sealsChecked(seals.size())
                .documentsChecked(documentsChecked)
                .unsealedDocuments(unsealed)
                .build();
    }

    /**
     * Constant-time comparison of two hex-encoded hash values (FindSecBugs
     * UNSAFE_HASH_EQUALS). Verification is detection, not an authentication
     * oracle — the compared values are recomputed from stored data, not
     * attacker-submitted — so the practical timing-channel exposure is
     * minimal; the constant-time comparison is defense in depth and keeps
     * every hash check in this verifier uniform. Null semantics match the
     * previous {@code String.equals} behavior: a null stored hash mismatches
     * any recomputed value. Hex encodings in this module are consistently
     * lowercase, so byte-wise comparison of the encodings is equivalent to
     * hash equality.
     */
    private static boolean hashEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private long countUnsealed(String afterId) {
        return entryRepository.countByContentHashNotNullAndIdGreaterThan(afterId);
    }

    private IntegrityReport issue(String type, AuditSeal seal, long documentsChecked, String detail) {
        return IntegrityReport.builder()
                .verified(false)
                .sealsChecked(0)
                .documentsChecked(documentsChecked)
                .firstIssue(IntegrityReport.IntegrityIssue.builder()
                        .type(type)
                        .sealSequence(seal.getSequence())
                        .detail(detail)
                        .build())
                .build();
    }
}
