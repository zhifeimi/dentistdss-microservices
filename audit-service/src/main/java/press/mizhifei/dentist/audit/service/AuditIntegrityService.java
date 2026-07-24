package press.mizhifei.dentist.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import press.mizhifei.dentist.audit.dto.IntegrityReport;
import press.mizhifei.dentist.audit.model.AuditEntry;
import press.mizhifei.dentist.audit.model.AuditSeal;
import press.mizhifei.dentist.audit.repository.AuditEntryRepository;
import press.mizhifei.dentist.audit.repository.AuditSealRepository;

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
 * <p>Detection, not prevention: a fully consistent rewrite of sealed
 * history would require controlling the sealer. Entries written before this
 * feature carry no content hash and are never sealed; they are invisible to
 * verification by design. Plain method, no transaction — standalone Mongo
 * as deployed has no multi-document transactions.
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
            if (!expectedPreviousHash.equals(seal.getPreviousSealHash())) {
                return issue("CHAIN_BROKEN", seal, documentsChecked,
                        "previousSealHash does not match the preceding seal's hash");
            }
            if (!AuditSealingService.computeSealHash(seal).equals(seal.getSealHash())) {
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
                if (!contentHasher.hash(doc).equals(doc.getContentHash())) {
                    return issue("DOCUMENT_MODIFIED", seal, documentsChecked,
                            "document " + doc.getId() + " no longer matches its contentHash");
                }
                documentsChecked++;
            }
            if (!AuditSealingService.computeBatchRoot(docs).equals(seal.getBatchRoot())) {
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
