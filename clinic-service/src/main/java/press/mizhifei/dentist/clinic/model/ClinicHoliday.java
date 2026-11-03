package press.mizhifei.dentist.clinic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * A day the clinic is closed or keeps special hours. A recurring holiday
 * repeats every year on the month and day of {@code holidayDate}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "clinic_holidays")
public class ClinicHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long clinicId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private LocalDate holidayDate;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private HolidayType type;

    @Column(nullable = false)
    private Boolean isFullDayClosure;

    private LocalTime specialOpeningTime;

    private LocalTime specialClosingTime;

    @Column(nullable = false)
    private Boolean isRecurring;

    @Column(length = 100)
    private String emergencyContact;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
