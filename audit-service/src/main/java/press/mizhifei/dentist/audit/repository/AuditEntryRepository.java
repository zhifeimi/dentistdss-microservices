package press.mizhifei.dentist.audit.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.audit.model.AuditEntry;

import java.util.List;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Repository
public interface AuditEntryRepository extends MongoRepository<AuditEntry, String> {

    /**
     * The unsealed, hashable prefix after {@code id} in {@code _id} order.
     * {@code contentHash != null} skips pre-feature documents forever; Mongo
     * ObjectIds are monotonic, so this is exactly the entries written since
     * tamper-evident ingestion that no seal covers yet. (If the derived
     * String-to-ObjectId range conversion ever misbehaves, replace with an
     * {@code @Query} taking {@code ObjectId} parameters.)
     */
    List<AuditEntry> findByIdGreaterThanAndContentHashNotNullOrderByIdAsc(String id, Pageable pageable);

    /** The exact sealed range, inclusive, for integrity verification. */
    List<AuditEntry> findByIdGreaterThanEqualAndIdLessThanEqualOrderByIdAsc(String firstId, String lastId);

    /** Hashable entries written after {@code id} — the current sealing backlog. */
    long countByContentHashNotNullAndIdGreaterThan(String id);
} 