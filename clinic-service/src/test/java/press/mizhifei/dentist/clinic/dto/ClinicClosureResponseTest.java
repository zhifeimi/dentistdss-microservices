package press.mizhifei.dentist.clinic.dto;

import org.junit.jupiter.api.Test;
import press.mizhifei.dentist.clinic.model.ClinicHoliday;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClinicClosureResponseTest {

    @Test
    void recurringLeapDayFallsOnThe28thInCommonYears() {
        ClinicHoliday leapDay = ClinicHoliday.builder()
                .holidayDate(LocalDate.of(2024, 2, 29)).isRecurring(true).build();

        assertTrue(ClinicClosureResponse.occursOn(leapDay, LocalDate.of(2027, 2, 28)));
        assertFalse(ClinicClosureResponse.occursOn(leapDay, LocalDate.of(2027, 3, 1)));
        assertTrue(ClinicClosureResponse.occursOn(leapDay, LocalDate.of(2028, 2, 29)));
        assertFalse(ClinicClosureResponse.occursOn(leapDay, LocalDate.of(2028, 2, 28)));
    }
}
