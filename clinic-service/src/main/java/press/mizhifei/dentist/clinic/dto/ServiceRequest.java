package press.mizhifei.dentist.clinic.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Data
@Builder
// Private: Jackson 3 would bind JSON through a visible all-args constructor
// and drop the @Builder.Default values for omitted fields.
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
public class ServiceRequest {
    
    @NotNull(message = "Clinic ID is required")
    private Long clinicId;
    
    @NotBlank(message = "Service name is required")
    private String name;
    
    private String description;
    
    @NotNull(message = "Duration is required")
    @Min(value = 15, message = "Duration must be at least 15 minutes")
    private Integer durationMinutes;
    
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Price must be greater than 0")
    private BigDecimal price;
    
    private String category;
    
    @Builder.Default
    private Boolean isActive = true;
} 