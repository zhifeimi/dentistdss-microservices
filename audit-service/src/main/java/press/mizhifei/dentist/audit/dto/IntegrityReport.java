package press.mizhifei.dentist.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a tamper-evidence verification pass over the audit seal chain
 * (AUDIT-01). {@code verified} is true when every seal and every sealed
 * document recomputes cleanly; otherwise {@code firstIssue} names the first
 * problem found (verification fails fast). {@code unsealedDocuments} is
 * informational backlog: hashable entries not yet covered by any seal
 * (including everything written since the last seal).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntegrityReport {

    private boolean verified;
    private long sealsChecked;
    private long documentsChecked;
    private long unsealedDocuments;
    private IntegrityIssue firstIssue;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class IntegrityIssue {
        /** e.g. SEQUENCE_GAP, CHAIN_BROKEN, DOCUMENT_MODIFIED. */
        private String type;
        private Long sealSequence;
        private String detail;
    }
}
