package press.mizhifei.dentist.clinic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Singleton row (id 1) coordinating the frontend rebuild trigger across
 * clinic-service replicas. Timestamps are UTC.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "public_directory_sync")
public class PublicDirectorySyncState {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    /** Fingerprint of the public payload the last successful rebuild was triggered for. */
    @Column(length = 64)
    private String publishedFingerprint;

    /** Last successful deploy hook call. */
    private LocalDateTime lastHookAt;

    /** Last deploy hook attempt, successful or not. */
    private LocalDateTime lastAttemptAt;

    /** Why the last attempt failed; never contains the hook URL. */
    @Column(length = 255)
    private String lastError;
}
