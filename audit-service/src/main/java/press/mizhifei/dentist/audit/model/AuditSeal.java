package press.mizhifei.dentist.audit.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A tamper-evident batch seal (AUDIT-01). Seals never touch the entry
 * documents themselves: each one covers a contiguous {@code _id} range of
 * content-hashed entries and chains to its predecessor, so any edit,
 * deletion, insertion, or chain surgery inside a sealed range is detectable
 * by recomputation. The unique {@code sequence} index is the double-sealing
 * backstop (relies on Mongo auto-index creation, which is on by default).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audit_seals")
public class AuditSeal {

    @Id
    private String id;

    /** 1-based, strictly increasing by one per seal. */
    @Indexed(unique = true)
    private long sequence;

    /** Covered entry {@code _id}s, inclusive. */
    private String firstId;
    private String lastId;

    /** Number of covered entries. */
    private long count;

    /** {@code sha256Hex(contentHash_1 + "\n" + ... + contentHash_n)} in {@code _id} order. */
    private String batchRoot;

    /** Predecessor's {@code sealHash}; the literal {@code GENESIS} for sequence 1. */
    private String previousSealHash;

    /** {@code sha256Hex(previousSealHash|sequence|firstId|lastId|count|batchRoot)}. */
    private String sealHash;

    private LocalDateTime sealedAt;
}
