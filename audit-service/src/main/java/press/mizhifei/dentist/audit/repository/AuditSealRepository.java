package press.mizhifei.dentist.audit.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.audit.model.AuditSeal;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditSealRepository extends MongoRepository<AuditSeal, String> {

    /** The most recent seal — the chain head the sealer extends. */
    Optional<AuditSeal> findTopByOrderBySequenceDesc();

    /** The full chain in sequence order, for integrity verification. */
    List<AuditSeal> findAllByOrderBySequenceAsc();
}
