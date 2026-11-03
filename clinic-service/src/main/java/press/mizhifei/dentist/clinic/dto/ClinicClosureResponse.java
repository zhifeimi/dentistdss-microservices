package press.mizhifei.dentist.clinic.dto;

import press.mizhifei.dentist.clinic.model.ClinicHoliday;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Whether a clinic takes bookings on one date, from its holidays.
 * {@code closed} means no bookings at all; otherwise a non-null
 * {@code openTime}/{@code closeTime} limits bookings to those special hours,
 * and both null means a normal day. {@code reason} names the holiday.
 */
public record ClinicClosureResponse(
        LocalDate date,
        boolean closed,
        String openTime,
        String closeTime,
        String reason) {

    public static ClinicClosureResponse of(List<ClinicHoliday> holidays, LocalDate date) {
        List<ClinicHoliday> today = holidays.stream().filter(h -> occursOn(h, date)).toList();
        if (today.isEmpty()) {
            return new ClinicClosureResponse(date, false, null, null, null);
        }
        for (ClinicHoliday h : today) {
            if (Boolean.TRUE.equals(h.getIsFullDayClosure())) {
                return new ClinicClosureResponse(date, true, null, null, h.getName());
            }
        }
        // Several special-hours holidays on one day: only the hours they all allow.
        LocalTime open = today.stream().map(ClinicHoliday::getSpecialOpeningTime).max(LocalTime::compareTo).orElseThrow();
        LocalTime close = today.stream().map(ClinicHoliday::getSpecialClosingTime).min(LocalTime::compareTo).orElseThrow();
        String reason = today.get(0).getName();
        if (!open.isBefore(close)) {
            return new ClinicClosureResponse(date, true, null, null, reason);
        }
        return new ClinicClosureResponse(date, false, hhmm(open), hhmm(close), reason);
    }

    /**
     * A one-off holiday falls on its own date; a recurring one on the same
     * month and day every year from its first date (29 February on the 28th in
     * common years, matching {@link HolidayResponse}).
     */
    static boolean occursOn(ClinicHoliday h, LocalDate date) {
        LocalDate first = h.getHolidayDate();
        if (!Boolean.TRUE.equals(h.getIsRecurring())) {
            return first.equals(date);
        }
        return !date.isBefore(first) && first.withYear(date.getYear()).equals(date);
    }

    private static String hhmm(LocalTime time) {
        return time.truncatedTo(ChronoUnit.MINUTES).toString();
    }
}
