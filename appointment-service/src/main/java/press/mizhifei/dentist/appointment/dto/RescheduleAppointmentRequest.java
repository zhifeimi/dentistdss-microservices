package press.mizhifei.dentist.appointment.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record RescheduleAppointmentRequest(
        @NotNull(message = "New appointment date is required")
        @FutureOrPresent(message = "New appointment date must not be in the past")
        LocalDate newDate,
        @NotNull(message = "New start time is required")
        LocalTime newStartTime,
        @NotNull(message = "New end time is required")
        LocalTime newEndTime) {
}
