package press.mizhifei.dentist.clinic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
public class ClinicResponse {
    private Long id;
    private String name;
    private String address;
    private String city;
    private String state;
    private String zipCode;
    private String country;
    private String phoneNumber;
    private String email;
    private String website;
    private String description;
    /** Public URL slug; assigned once the clinic is approved. */
    private String slug;
    /** "Show my clinic in public search". */
    private Boolean listedPublicly;
    private Double latitude;
    private Double longitude;
    /** Weekly opening hours; only on single-clinic responses, {@code null} in lists. */
    private List<OperatingHoursDto> operatingHours;
} 