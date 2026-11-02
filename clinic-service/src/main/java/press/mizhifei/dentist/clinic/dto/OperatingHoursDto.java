package press.mizhifei.dentist.clinic.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalTime;

/**
 * One day of opening hours, matching {@code OperatingHours} in the
 * frontend's {@code src/types/api.ts}: {@code dayOfWeek} 0 (Sunday) to
 * 6 (Saturday), times as {@code HH:mm}. A closed day still carries times.
 */
public record OperatingHoursDto(
        @NotNull(message = "dayOfWeek is required")
        @Min(value = 0, message = "dayOfWeek must be 0 (Sunday) to 6 (Saturday)")
        @Max(value = 6, message = "dayOfWeek must be 0 (Sunday) to 6 (Saturday)")
        Integer dayOfWeek,

        @NotNull(message = "openTime is required")
        @Pattern(regexp = TIME_PATTERN, message = "openTime must be HH:mm")
        String openTime,

        @NotNull(message = "closeTime is required")
        @Pattern(regexp = TIME_PATTERN, message = "closeTime must be HH:mm")
        String closeTime,

        @NotNull(message = "isOpen is required")
        Boolean isOpen) {

    public static final String TIME_PATTERN = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    @JsonIgnore
    @AssertTrue(message = "openTime must be before closeTime on an open day")
    public boolean isTimeRangeValid() {
        if (!Boolean.TRUE.equals(isOpen) || !isTime(openTime) || !isTime(closeTime)) {
            // Missing or malformed values are reported by the field constraints.
            return true;
        }
        return LocalTime.parse(openTime).isBefore(LocalTime.parse(closeTime));
    }

    private static boolean isTime(String value) {
        return value != null && value.matches(TIME_PATTERN);
    }
}
