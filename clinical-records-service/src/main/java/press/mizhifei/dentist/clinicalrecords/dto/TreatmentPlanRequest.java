package press.mizhifei.dentist.clinicalrecords.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TreatmentPlanRequest {

    private Long patientId;

    private Long dentistId;

    private Long clinicId;

    @Size(max = 255, message = "Plan name must not exceed 255 characters")
    private String planName;

    private String description;

    private BigDecimal totalCost;

    private BigDecimal insuranceCoverage;

    private BigDecimal patientCost;

    private Integer parentPlanId;

    @Valid
    private List<TreatmentPlanItemRequest> items;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TreatmentPlanItemRequest {

        private Integer serviceId;

        @Size(max = 10, message = "Tooth number must not exceed 10 characters")
        private String toothNumber;

        private String description;

        private BigDecimal cost;

        private Integer sequenceOrder;

        private String notes;
    }
}
