package press.mizhifei.dentist.clinicalrecords.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ServiceVisitRequest {

    @NotNull(message = "Patient ID is required")
    private Long patientId;

    private Long dentistId;

    private Long clinicId;

    private Long appointmentId;

    @NotNull(message = "Visit type is required")
    private String visitType;

    @NotNull(message = "Visit date is required")
    private LocalDateTime visitDate;

    private LocalDateTime checkInTime;

    private LocalDateTime checkOutTime;

    private String notes;
}
