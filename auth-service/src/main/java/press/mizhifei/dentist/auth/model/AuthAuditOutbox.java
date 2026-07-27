package press.mizhifei.dentist.auth.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * Durable outbox for audit events (AUDIT-01). A row is written in the same
 * transactional boundary as the security-critical mutation it records, so
 * the event exists if and only if the mutation committed. The scheduled
 * relay delivers rows to audit-service and deletes them only after a
 * confirmed delivery; failures are retried with backoff and rows are never
 * expired or purged — security evidence is never discarded.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "auth_audit_outbox", indexes = {
        @Index(name = "idx_auth_audit_outbox_pending", columnList = "created_at")
})
public class AuthAuditOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "target", length = 255)
    private String target;

    @Column(name = "asserted_user_id")
    private Long assertedUserId;

    @Column(name = "asserted_clinic_id")
    private Long assertedClinicId;

    @Type(JsonType.class)
    @Column(name = "context", columnDefinition = "jsonb")
    private JsonNode context;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 512)
    private String lastError;
}
