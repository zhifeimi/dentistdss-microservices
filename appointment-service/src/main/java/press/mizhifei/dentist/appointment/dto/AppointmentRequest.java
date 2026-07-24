package press.mizhifei.dentist.appointment.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
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
@AllArgsConstructor
@NoArgsConstructor
public class AppointmentRequest {
    
    private Long patientId;
    
    @NotNull(message = "Dentist ID is required")
    private Long dentistId;
    
    @NotNull(message = "Clinic ID is required")
    private Long clinicId;

    @Deprecated
    private Long createdBy;
    
    private Integer serviceId;
    
    @NotNull(message = "Appointment date is required")
    @FutureOrPresent(message = "Appointment date must not be in the past")
    private LocalDate appointmentDate;
    
    @NotNull(message = "Start time is required")
    private LocalTime startTime;
    
    @NotNull(message = "End time is required")
    private LocalTime endTime;
    
    @Size(max = 500, message = "Reason for visit must not exceed 500 characters")
    private String reasonForVisit;
    
    @Size(max = 1000, message = "Symptoms description must not exceed 1000 characters")
    private String symptoms;
    
    @Pattern(
            regexp = "(?i)ROUTINE|MODERATE|URGENT|EMERGENCY",
            message = "Urgency level must be ROUTINE, MODERATE, URGENT, or EMERGENCY")
    private String urgencyLevel;
    
    @Size(max = 1000, message = "Notes must not exceed 1000 characters")
    private String notes;
}
