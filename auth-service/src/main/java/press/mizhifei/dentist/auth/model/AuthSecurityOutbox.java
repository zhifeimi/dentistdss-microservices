package press.mizhifei.dentist.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Durable outbox for Redis session-family tombstones. A row is written in the
 * same transactional boundary as the database revocation, so a failed or
 * interrupted Redis publication can always be replayed until the tombstone is
 * guaranteed to exist. Rows are deleted on successful publication and purged
 * once the family lifetime has passed (at which point any stale Redis
 * "active" key has expired on its own and the gateway fails closed).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "auth_security_outbox", indexes = {
        @Index(name = "idx_auth_security_outbox_pending", columnList = "expires_at,last_attempt_at")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_auth_security_outbox_family", columnNames = {"user_id", "family_id"})
})
public class AuthSecurityOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "tombstone_ttl_millis", nullable = false)
    private long tombstoneTtlMillis;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
