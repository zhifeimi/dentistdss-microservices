package press.mizhifei.dentist.appointment.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "appointments")
public class Appointment {
    
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "appointment_seq")
    @SequenceGenerator(name = "appointment_seq", sequenceName = "appointment_id_seq", allocationSize = 1, initialValue = 10000)
    private Long id;
    
    @Column(name = "patient_id", nullable = false)
    private Long patientId;
    
    @Column(name = "dentist_id", nullable = false)
    private Long dentistId;
    
    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;
    
    @Column(name = "service_id")
    private Integer serviceId;
    
    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;
    
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;
    
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
    
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "appointment_status")
    @Builder.Default
    private AppointmentStatus status = AppointmentStatus.REQUESTED;
    
    @Column(name = "reason_for_visit", columnDefinition = "TEXT")
    private String reasonForVisit;
    
    @Column(columnDefinition = "TEXT")
    private String symptoms;
    
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "urgency_level")
    @Builder.Default
    private UrgencyLevel urgency = UrgencyLevel.ROUTINE;
    
    @Column(name = "ai_triage_notes", columnDefinition = "TEXT")
    private String aiTriageNotes;
    
    @Column(columnDefinition = "TEXT")
    private String notes;
    
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "confirmed_by")
    private Long confirmedBy;
    
    @Column(name = "cancelled_by")
    private Long cancelledBy;
    
    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;
    
    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
