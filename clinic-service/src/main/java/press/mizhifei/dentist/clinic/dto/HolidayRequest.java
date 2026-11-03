package press.mizhifei.dentist.clinic.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import press.mizhifei.dentist.clinic.model.HolidayType;

import java.time.LocalDate;
import java.time.LocalTime;

import static press.mizhifei.dentist.clinic.dto.OperatingHoursDto.TIME_PATTERN;

/**
 * Create or replace a clinic holiday, matching {@code CreateHolidayRequest} in
 * the frontend. A day that is not a full closure needs both special times.
 */
public record HolidayRequest(
        @NotBlank(message = "name is required")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @NotNull(message = "holidayDate is required")
        LocalDate holidayDate,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @NotNull(message = "type is required")
        HolidayType type,

        @NotNull(message = "isFullDayClosure is required")
        Boolean isFullDayClosure,

        @Pattern(regexp = TIME_PATTERN, message = "specialOpeningTime must be HH:mm")
        String specialOpeningTime,

        @Pattern(regexp = TIME_PATTERN, message = "specialClosingTime must be HH:mm")
        String specialClosingTime,

        @NotNull(message = "isRecurring is required")
        Boolean isRecurring,

        @Size(max = 100, message = "emergencyContact must be at most 100 characters")
        String emergencyContact) {

    @JsonIgnore
    @AssertTrue(message = "Special hours need an opening time before the closing time")
    public boolean isSpecialHoursValid() {
        if (!Boolean.FALSE.equals(isFullDayClosure)) {
            return true;
        }
        if (!isTime(specialOpeningTime) || !isTime(specialClosingTime)) {
            return false;
        }
        return LocalTime.parse(specialOpeningTime).isBefore(LocalTime.parse(specialClosingTime));
    }

    private static boolean isTime(String value) {
        return value != null && value.matches(TIME_PATTERN);
    }
}
