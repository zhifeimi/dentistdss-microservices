package press.mizhifei.dentist.clinic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A slug a clinic used before a rename. The slug stays reserved for that
 * clinic so the old public URL keeps redirecting to it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "clinic_slug_history")
public class ClinicSlugHistory {

    @Id
    @Column(length = 160)
    private String slug;

    @Column(nullable = false)
    private Long clinicId;

    @Column(nullable = false)
    private LocalDateTime retiredAt;
}
