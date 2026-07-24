package press.mizhifei.dentist.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import press.mizhifei.dentist.audit.model.AuditEntry;
import press.mizhifei.dentist.audit.model.AuditSeal;
import press.mizhifei.dentist.audit.repository.AuditEntryRepository;
import press.mizhifei.dentist.audit.repository.AuditSealRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Periodically seals content-hashed audit entries into a chained, strictly
 * sequential batch chain (AUDIT-01). Standalone Mongo as deployed has no
 * multi-document transactions, so tamper-evidence is built from single-
 * document atomic steps: entries carry their own hash, and each seal
 * references a contiguous {@code _id} range plus the previous seal's hash.
 *
 * <p>Single-writer by construction: audit-service runs one replica,
 * {@code fixedDelay} prevents intra-JVM overlap, and the unique
 * {@code sequence} index is the cross-JVM backstop (a second sealer's save
 * fails with {@link DuplicateKeyException} and the round stops). Detection,
 * not prevention: a fully consistent rewrite would require controlling this
 * sealer — see {@link AuditIntegrityService} for what gets detected.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditSealingService {

    static final String GENESIS = "GENESIS";
    static final String FIRST_OBJECT_ID = "000000000000000000000000";

    private final AuditEntryRepository entryRepository;
    private final AuditSealRepository sealRepository;

    @Value("${app.audit.sealing.enabled:true}")
    private boolean sealingEnabled;

    @Value("${app.audit.sealing.batch-size:1000}")
    private int batchSize;

    @Value("${app.audit.sealing.max-rounds-per-tick:10}")
    private int maxRoundsPerTick;

    @Scheduled(
            fixedDelayString = "${app.audit.sealing.interval-ms:60000}",
            initialDelayString = "${app.audit.sealing.initial-delay-ms:30000}")
    public void sealPendingEntries() {
        if (!sealingEnabled) {
            return;
        }
        try {
            for (int round = 0; round < maxRoundsPerTick; round++) {
                if (!sealOneBatch()) {
                    return;
                }
            }
        } catch (RuntimeException ex) {
            log.error("Audit sealing cycle failed; retrying next interval", ex);
        }
    }

    /**
     * Seals the next unsealed batch, if any. Returns {@code true} only when
     * a full batch was sealed, so the caller keeps catching up on backlogs;
     * a partial batch means the chain is caught up.
     */
    boolean sealOneBatch() {
        AuditSeal last = sealRepository.findTopByOrderBySequenceDesc().orElse(null);
        String afterId = last == null ? FIRST_OBJECT_ID : last.getLastId();
        List<AuditEntry> batch = entryRepository
                .findByIdGreaterThanAndContentHashNotNullOrderByIdAsc(afterId, Pageable.ofSize(batchSize));
        if (batch.isEmpty()) {
            return false;
        }
        long sequence = last == null ? 1L : last.getSequence() + 1;
        AuditSeal seal = AuditSeal.builder()
                .sequence(sequence)
                .firstId(batch.getFirst().getId())
                .lastId(batch.getLast().getId())
                .count(batch.size())
                .batchRoot(computeBatchRoot(batch))
                .previousSealHash(last == null ? GENESIS : last.getSealHash())
                .build();
        seal.setSealHash(computeSealHash(seal));
        seal.setSealedAt(LocalDateTime.now());
        try {
            sealRepository.save(seal);
        } catch (DuplicateKeyException ex) {
            log.warn("Concurrent audit sealer detected at sequence {}; stopping this round", sequence);
            return false;
        }
        return batch.size() == batchSize;
    }

    /** Root over the stored content hashes, in {@code _id} order. */
    static String computeBatchRoot(List<AuditEntry> batch) {
        String joined = batch.stream()
                .map(AuditEntry::getContentHash)
                .collect(Collectors.joining("\n"));
        return AuditHashes.sha256Hex(joined);
    }

    /** The seal's chained self-hash over its immutable fields. */
    static String computeSealHash(AuditSeal seal) {
        return AuditHashes.sha256Hex(
                seal.getPreviousSealHash() + "|"
                        + seal.getSequence() + "|"
                        + seal.getFirstId() + "|"
                        + seal.getLastId() + "|"
                        + seal.getCount() + "|"
                        + seal.getBatchRoot());
    }
}
