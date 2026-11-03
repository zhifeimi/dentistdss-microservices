package press.mizhifei.dentist.appointment.client;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * clinic-service's view of one date ({@code GET /clinic/{id}/holidays/closure}):
 * {@code closed} blocks every booking; otherwise non-null special hours limit
 * bookings to {@code openTime}–{@code closeTime}.
 */
public record ClinicClosureResponse(
        LocalDate date,
        boolean closed,
        LocalTime openTime,
        LocalTime closeTime,
        String reason) {

    public boolean hasSpecialHours() {
        return !closed && openTime != null && closeTime != null;
    }

    /** Whether a booking from {@code start} to {@code end} fits the clinic's hours that day. */
    public boolean allows(LocalTime start, LocalTime end) {
        if (closed) {
            return false;
        }
        return !hasSpecialHours() || (!start.isBefore(openTime) && !end.isAfter(closeTime));
    }
}
