package press.mizhifei.dentist.clinic.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import press.mizhifei.dentist.clinic.config.ClinicSecurityConfig;
import press.mizhifei.dentist.clinic.exception.GlobalExceptionHandler;
import press.mizhifei.dentist.clinic.dto.HolidayResponse;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.model.ClinicHoliday;
import press.mizhifei.dentist.clinic.model.HolidayType;
import press.mizhifei.dentist.clinic.repository.ClinicHolidayRepository;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.security.RedisAccessTokenJwtDecoder;
import press.mizhifei.dentist.security.ServletBearerTokenFailureHandler;
import press.mizhifei.dentist.security.ServletJwtResourceServerCustomizer;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {HolidayControllerTest.TestConfig.class, ClinicSecurityConfig.class,
        GlobalExceptionHandler.class})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class HolidayControllerTest {

    /** 2026-11-03 10:00 in Sydney. */
    private static final Instant NOW = Instant.parse("2026-11-02T23:00:00Z");

    private static final String FULL_DAY_BODY = """
            {"name":"Christmas Day","holidayDate":"2026-12-25","description":"","type":"NATIONAL_HOLIDAY",
             "isFullDayClosure":true,"isRecurring":true,"emergencyContact":" 0400 000 000 "}""";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ClinicHolidayRepository holidayRepository;

    @Autowired
    private ClinicRepository clinicRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(holidayRepository, clinicRepository);
        when(clinicRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(Clinic.builder().id(7L).build()));
        when(holidayRepository.saveAndFlush(any())).thenAnswer(call -> {
            ClinicHoliday h = call.getArgument(0);
            if (h.getId() == null) {
                h.setId(99L);
            }
            return h;
        });
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).apply(springSecurity()).build();
    }

    @Test
    void clinicAdminCannotReadAnotherClinicsHolidays() throws Exception {
        mockMvc.perform(get("/clinic/{clinicId}/holidays", 8).with(clinicAdmin(7L)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(holidayRepository);
    }

    @Test
    void patientCannotReadHolidays() throws Exception {
        mockMvc.perform(get("/clinic/{clinicId}/holidays", 7).with(jwt()
                        .jwt(t -> t.subject("42").claim("roles", List.of("PATIENT")).claim("clinicId", 7L))
                        .authorities(new SimpleGrantedAuthority("ROLE_PATIENT"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(holidayRepository);
    }

    @Test
    void clinicAdminCreatesFullDayHoliday() throws Exception {

        mockMvc.perform(post("/clinic/{clinicId}/holidays", 7).with(clinicAdmin(7L))
                        .contentType("application/json").content(FULL_DAY_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataObject.id").value(99))
                .andExpect(jsonPath("$.dataObject.typeDisplayName").value("National holiday"))
                .andExpect(jsonPath("$.dataObject.daysUntilHoliday").value(52))
                .andExpect(jsonPath("$.dataObject.isUpcoming").value(false));

        ArgumentCaptor<ClinicHoliday> saved = ArgumentCaptor.forClass(ClinicHoliday.class);
        verify(holidayRepository).saveAndFlush(saved.capture());
        assertEquals(7L, saved.getValue().getClinicId());
        assertNull(saved.getValue().getDescription(), "blank description is stored as null");
        assertEquals("0400 000 000", saved.getValue().getEmergencyContact());
        assertNull(saved.getValue().getSpecialOpeningTime());
    }

    @Test
    void duplicateDateConstraintReturnsConflict() throws Exception {
        doThrow(new DataIntegrityViolationException("duplicate", new ConstraintViolationException(
                "duplicate", new SQLException("duplicate", "23505"), "insert", "uk_clinic_holidays_date")))
                .when(holidayRepository).saveAndFlush(any());

        mockMvc.perform(post("/clinic/7/holidays").with(clinicAdmin(7L))
                        .contentType("application/json").content(FULL_DAY_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void unrelatedIntegrityFailuresAreNotReportedAsDuplicateDates() throws Exception {
        doThrow(new DataIntegrityViolationException("not-null constraint"))
                .when(holidayRepository).saveAndFlush(any());

        mockMvc.perform(post("/clinic/7/holidays").with(clinicAdmin(7L))
                        .contentType("application/json").content(FULL_DAY_BODY))
                .andExpect(status().isInternalServerError());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "not-a-date"})
    void malformedDatesReturnBadRequest(String date) throws Exception {
        mockMvc.perform(post("/clinic/7/holidays").with(clinicAdmin(7L))
                        .contentType("application/json").content(FULL_DAY_BODY.replace("2026-12-25", date)))
                .andExpect(status().isBadRequest());
        verify(holidayRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownHolidayTypeReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/clinic/7/holidays/5").with(clinicAdmin(7L))
                        .contentType("application/json").content(FULL_DAY_BODY.replace("NATIONAL_HOLIDAY", "UNKNOWN")))
                .andExpect(status().isBadRequest());
        verify(holidayRepository, never()).saveAndFlush(any());
    }

    @Test
    void specialHoursNeedAValidRange() throws Exception {
        String body = FULL_DAY_BODY.replace("\"isFullDayClosure\":true",
                "\"isFullDayClosure\":false,\"specialOpeningTime\":\"14:00\",\"specialClosingTime\":\"10:00\"");

        mockMvc.perform(post("/clinic/{clinicId}/holidays", 7).with(clinicAdmin(7L))
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verify(holidayRepository, never()).saveAndFlush(any());
    }

    @Test
    void cannotUpdateAHolidayThroughAnotherClinicsPath() throws Exception {
        when(holidayRepository.findById(5L)).thenReturn(Optional.of(holiday(5L, 8L, LocalDate.of(2026, 12, 1), false)));

        mockMvc.perform(put("/clinic/{clinicId}/holidays/{id}", 7, 5).with(clinicAdmin(7L))
                        .contentType("application/json").content(FULL_DAY_BODY))
                .andExpect(status().isNotFound());
        verify(holidayRepository, never()).saveAndFlush(any());
    }

    @Test
    void nameOnlyEditPreservesLeapDayRecurrenceAnchor() throws Exception {
        ClinicHoliday leapDay = holiday(5L, 7L, LocalDate.of(2024, 2, 29), true);
        when(holidayRepository.findById(5L)).thenReturn(Optional.of(leapDay));
        when(holidayRepository.findByClinicIdOrderByHolidayDateAsc(7L)).thenReturn(List.of(leapDay));

        mockMvc.perform(get("/clinic/7/holidays").with(clinicAdmin(7L)))
                .andExpect(jsonPath("$.dataObject[0].holidayDate").value("2027-02-28"));
        mockMvc.perform(put("/clinic/7/holidays/5").with(clinicAdmin(7L))
                        .contentType("application/json")
                        .content(FULL_DAY_BODY.replace("2026-12-25", "2027-02-28")))
                .andExpect(status().isOk());

        assertEquals(LocalDate.of(2024, 2, 29), leapDay.getHolidayDate());
        assertEquals(LocalDate.of(2028, 2, 29),
                HolidayResponse.of(leapDay, LocalDate.of(2028, 1, 1)).holidayDate());
        mockMvc.perform(get("/clinic/7/holidays").with(clinicAdmin(7L)))
                .andExpect(jsonPath("$.dataObject[0].recurrenceAnchorDate").value("2024-02-29"));
    }

    @Test
    void projectedDateFromAnOlderFormPreservesTheAnchor() throws Exception {
        ClinicHoliday leapDay = holiday(5L, 7L, LocalDate.of(2024, 2, 29), true);
        when(holidayRepository.findById(5L)).thenReturn(Optional.of(leapDay));

        mockMvc.perform(put("/clinic/7/holidays/5").with(clinicAdmin(7L))
                        .contentType("application/json")
                        .content(FULL_DAY_BODY.replace("2026-12-25", "2026-02-28")))
                .andExpect(status().isOk());
        assertEquals(LocalDate.of(2024, 2, 29), leapDay.getHolidayDate());
    }

    @Test
    void explicitAnchorAllowsChangingLeapDayToFebruary28() throws Exception {
        ClinicHoliday leapDay = holiday(5L, 7L, LocalDate.of(2024, 2, 29), true);
        when(holidayRepository.findById(5L)).thenReturn(Optional.of(leapDay));
        String body = FULL_DAY_BODY.replace("2026-12-25", "2027-02-28")
                .replace("\"isRecurring\":true", "\"isRecurring\":true,\"recurrenceAnchorDate\":\"2026-02-28\"");

        mockMvc.perform(put("/clinic/7/holidays/5").with(clinicAdmin(7L))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        assertEquals(LocalDate.of(2026, 2, 28), leapDay.getHolidayDate());
    }

    @Test
    void changingTheProjectedDateChangesTheRecurrenceAnchor() throws Exception {
        ClinicHoliday leapDay = holiday(5L, 7L, LocalDate.of(2024, 2, 29), true);
        when(holidayRepository.findById(5L)).thenReturn(Optional.of(leapDay));

        mockMvc.perform(put("/clinic/7/holidays/5").with(clinicAdmin(7L))
                        .contentType("application/json").content(FULL_DAY_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataObject.recurrenceAnchorDate").value("2026-12-25"));
        assertEquals(LocalDate.of(2026, 12, 25), leapDay.getHolidayDate());
    }

    @Test
    void upcomingRollsRecurringHolidaysForwardAndDropsPastOnes() throws Exception {
        when(holidayRepository.findByClinicIdOrderByHolidayDateAsc(7L)).thenReturn(List.of(
                holiday(1L, 7L, LocalDate.of(2020, 11, 10), true),   // recurring: next is 2026-11-10
                holiday(2L, 7L, LocalDate.of(2026, 10, 1), false),   // one-off, already past
                holiday(3L, 7L, LocalDate.of(2026, 11, 3), false),   // today
                holiday(4L, 7L, LocalDate.of(2027, 1, 26), false))); // more than 30 days away

        mockMvc.perform(get("/clinic/{clinicId}/holidays/upcoming", 7).with(clinicAdmin(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataObject.length()").value(2))
                .andExpect(jsonPath("$.dataObject[0].id").value(3))
                .andExpect(jsonPath("$.dataObject[0].daysUntilHoliday").value(0))
                .andExpect(jsonPath("$.dataObject[1].id").value(1))
                .andExpect(jsonPath("$.dataObject[1].holidayDate").value("2026-11-10"))
                .andExpect(jsonPath("$.dataObject[1].specialOpeningTime").value("09:30"));
    }

    @Test
    void anySignedInUserSeesAFullDayClosureFromARecurringHoliday() throws Exception {
        ClinicHoliday christmas = holiday(1L, 7L, LocalDate.of(2020, 12, 25), true);
        christmas.setIsFullDayClosure(true);
        christmas.setName("Christmas Day");
        when(holidayRepository.findByClinicIdOrderByHolidayDateAsc(7L)).thenReturn(List.of(christmas));

        mockMvc.perform(get("/clinic/{clinicId}/holidays/closure", 7).param("date", "2026-12-25").with(patient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataObject.closed").value(true))
                .andExpect(jsonPath("$.dataObject.reason").value("Christmas Day"));
    }

    @Test
    void specialHoursOnTheSameDayNarrowToWhatBothAllow() throws Exception {
        ClinicHoliday later = holiday(2L, 7L, LocalDate.of(2026, 12, 24), false);
        later.setSpecialOpeningTime(LocalTime.of(10, 0));
        later.setSpecialClosingTime(LocalTime.of(15, 0));
        when(holidayRepository.findByClinicIdOrderByHolidayDateAsc(7L)).thenReturn(List.of(
                holiday(1L, 7L, LocalDate.of(2025, 12, 24), true), // 09:30-13:00 every year
                later));

        mockMvc.perform(get("/clinic/{clinicId}/holidays/closure", 7).param("date", "2026-12-24").with(patient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataObject.closed").value(false))
                .andExpect(jsonPath("$.dataObject.openTime").value("10:00"))
                .andExpect(jsonPath("$.dataObject.closeTime").value("13:00"));
    }

    @Test
    void ordinaryDaysAndYearsBeforeARecurringHolidayStartAreOpen() throws Exception {
        when(holidayRepository.findByClinicIdOrderByHolidayDateAsc(7L))
                .thenReturn(List.of(holiday(1L, 7L, LocalDate.of(2027, 1, 26), true)));

        mockMvc.perform(get("/clinic/{clinicId}/holidays/closure", 7).param("date", "2026-01-26").with(patient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataObject.closed").value(false))
                .andExpect(jsonPath("$.dataObject.openTime").doesNotExist());
    }

    @Test
    void closureNeedsASignedInUser() throws Exception {
        mockMvc.perform(get("/clinic/{clinicId}/holidays/closure", 7).param("date", "2026-12-25"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(holidayRepository);
    }

    private static RequestPostProcessor patient() {
        return jwt().jwt(t -> t.subject("42").claim("roles", List.of("PATIENT")))
                .authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
    }

    private static ClinicHoliday holiday(Long id, Long clinicId, LocalDate date, boolean recurring) {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 0, 0);
        return ClinicHoliday.builder().id(id).clinicId(clinicId).name("H" + id).holidayDate(date)
                .type(HolidayType.CLINIC_CLOSURE).isFullDayClosure(false)
                .specialOpeningTime(LocalTime.of(9, 30)).specialClosingTime(LocalTime.of(13, 0))
                .isRecurring(recurring).createdAt(created).updatedAt(created).build();
    }

    private static RequestPostProcessor clinicAdmin(Long clinicId) {
        return jwt().jwt(t -> t.subject("55").claim("roles", List.of("CLINIC_ADMIN")).claim("clinicId", clinicId))
                .authorities(new SimpleGrantedAuthority("ROLE_CLINIC_ADMIN"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        ClinicHolidayRepository holidayRepository() {
            return mock(ClinicHolidayRepository.class);
        }

        @Bean
        ClinicRepository clinicRepository() {
            return mock(ClinicRepository.class);
        }

        @Bean
        HolidayController holidayController(ClinicHolidayRepository holidays, ClinicRepository clinics) {
            return new HolidayController(holidays, clinics, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        ServletJwtResourceServerCustomizer servletJwtResourceServerCustomizer() {
            return new ServletJwtResourceServerCustomizer(
                    mock(RedisAccessTokenJwtDecoder.class),
                    new JwtAuthenticationConverter(),
                    new ServletBearerTokenFailureHandler());
        }
    }
}
