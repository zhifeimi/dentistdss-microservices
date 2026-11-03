package press.mizhifei.dentist.clinic.dto;

import press.mizhifei.dentist.clinic.model.ClinicHoliday;
import press.mizhifei.dentist.clinic.model.HolidayType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * A clinic holiday as the dashboard shows it ({@code Holiday} in the
 * frontend). {@code holidayDate} is the next occurrence for a recurring
 * holiday; {@code daysUntilHoliday} is negative once a one-off date has passed.
 */
public record HolidayResponse(
        Long id,
        Long clinicId,
        String name,
        LocalDate holidayDate,
        String description,
        HolidayType type,
        String typeDisplayName,
        Boolean isFullDayClosure,
        String specialOpeningTime,
        String specialClosingTime,
        Boolean isRecurring,
        String emergencyContact,
        Boolean isUpcoming,
        Long daysUntilHoliday,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** How far ahead a holiday counts as upcoming. */
    public static final int UPCOMING_DAYS = 30;

    public static HolidayResponse of(ClinicHoliday h, LocalDate today) {
        LocalDate date = nextOccurrence(h, today);
        long days = ChronoUnit.DAYS.between(today, date);
        return new HolidayResponse(
                h.getId(),
                h.getClinicId(),
                h.getName(),
                date,
                h.getDescription(),
                h.getType(),
                h.getType().displayName(),
                h.getIsFullDayClosure(),
                hhmm(h.getSpecialOpeningTime()),
                hhmm(h.getSpecialClosingTime()),
                h.getIsRecurring(),
                h.getEmergencyContact(),
                days >= 0 && days <= UPCOMING_DAYS,
                days,
                h.getCreatedAt(),
                h.getUpdatedAt());
    }

    /**
     * A recurring holiday's next date on or after {@code today}; a one-off
     * holiday's own date. 29 February falls on the 28th in common years.
     */
    static LocalDate nextOccurrence(ClinicHoliday h, LocalDate today) {
        LocalDate date = h.getHolidayDate();
        if (!Boolean.TRUE.equals(h.getIsRecurring()) || !date.isBefore(today)) {
            return date;
        }
        LocalDate thisYear = date.withYear(today.getYear());
        return thisYear.isBefore(today) ? date.withYear(today.getYear() + 1) : thisYear;
    }

    private static String hhmm(LocalTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MINUTES).toString();
    }
}
