package press.mizhifei.dentist.clinic.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Data
@NoArgsConstructor
public class ClinicUpdateRequest {
    
    @Size(max = 100, message = "Clinic name must not exceed 100 characters")
    private String name;

    @Size(max = 200, message = "Address must not exceed 200 characters")
    private String address;

    @Size(max = 50, message = "City must not exceed 50 characters")
    private String city;

    @Size(max = 50, message = "State must not exceed 50 characters")
    private String state;

    @Size(max = 20, message = "Zip code must not exceed 20 characters")
    private String zipCode;

    @Size(max = 50, message = "Country must not exceed 50 characters")
    private String country;

    @Size(max = 30, message = "Phone number must not exceed 30 characters")
    private String phoneNumber;

    @Email(message = "Invalid email format")
    @Size(max = 100, message = "Email must not exceed 100 characters")
    private String email;

    /** Blank clears the website. */
    @Size(max = 255, message = "Website must not exceed 255 characters")
    @Pattern(regexp = "^(\\s*|https?://\\S+)$", flags = Pattern.Flag.CASE_INSENSITIVE,
            message = "Website must start with http:// or https://")
    private String website;

    /** Shown on the public clinic page. Blank clears it. */
    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    /** Map location; set both together. */
    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
    private Double latitude;

    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
    private Double longitude;

    /** "Show my clinic in public search". */
    private Boolean listedPublicly;

    /**
     * The full weekly schedule, replacing the stored one: one entry per day
     * at most; an empty list clears it; {@code null} leaves it unchanged.
     */
    @Size(max = 7, message = "Operating hours hold at most one entry per day")
    private List<@Valid @NotNull(message = "Operating hours entries must not be null") OperatingHoursDto> operatingHours;

    @JsonIgnore
    @AssertTrue(message = "Latitude and longitude must be set together")
    public boolean isLocationComplete() {
        return (latitude == null) == (longitude == null);
    }

    @JsonIgnore
    @AssertTrue(message = "Operating hours must not repeat a day")
    public boolean isOperatingDaysUnique() {
        if (operatingHours == null) {
            return true;
        }
        HashSet<Integer> days = new HashSet<>();
        return operatingHours.stream()
                .filter(Objects::nonNull)
                .map(OperatingHoursDto::dayOfWeek)
                .filter(Objects::nonNull)
                .allMatch(days::add);
    }
}
