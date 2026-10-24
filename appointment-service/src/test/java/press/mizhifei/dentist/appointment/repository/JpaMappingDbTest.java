package press.mizhifei.dentist.appointment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import press.mizhifei.dentist.appointment.model.Appointment;
import press.mizhifei.dentist.appointment.model.AppointmentStatus;
import press.mizhifei.dentist.appointment.model.UrgencyLevel;

/**
 * Repository and column-mapping checks against the real Flyway schema, which
 * the mocked unit tests cannot see: appointment status and urgency are
 * PostgreSQL named enums, so JPQL parameters compared with them must bind as
 * those types rather than varchar.
 *
 * <p>
 * Skipped unless {@code TEST_DATABASE_URL} is set (local Docker or the CI
 * postgres service container). Each test rolls back.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}",
        "spring.datasource.username=${TEST_DATABASE_USERNAME}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD}",
        "spring.cloud.config.enabled=false" })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JpaMappingDbTest {

    private static final long DENTIST_ID = 9L;
    private static final LocalDate DATE = LocalDate.of(2031, 3, 4);
    /** Same statuses AppointmentService treats as not occupying a slot. */
    private static final List<AppointmentStatus> NON_BLOCKING = List.of(
            AppointmentStatus.CANCELLED,
            AppointmentStatus.NO_SHOW);

    /** JPA only: the application class also enables Feign and discovery. */
    @Configuration
    @EntityScan(basePackageClasses = Appointment.class)
    @EnableJpaRepositories(basePackageClasses = AppointmentRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private AppointmentRepository appointments;

    @Autowired
    private EntityManager entityManager;

    @Test
    void slotQueriesExcludeNonBlockingStatuses() {
        Appointment booked = appointments.saveAndFlush(appointment(LocalTime.of(9, 0), AppointmentStatus.REQUESTED));
        appointments.saveAndFlush(appointment(LocalTime.of(10, 0), AppointmentStatus.CANCELLED));

        assertThat(appointments.findSlotBlockingAppointments(DENTIST_ID, DATE, NON_BLOCKING))
                .extracting(Appointment::getId).containsExactly(booked.getId());
        assertThat(appointments.findConflictingAppointments(
                DENTIST_ID, DATE, LocalTime.of(9, 15), LocalTime.of(9, 45), NON_BLOCKING))
                .extracting(Appointment::getId).containsExactly(booked.getId());
        assertThat(appointments.findConflictingAppointments(
                DENTIST_ID, DATE, LocalTime.of(10, 0), LocalTime.of(10, 30), NON_BLOCKING))
                .isEmpty();
    }

    @Test
    void enumColumnsRoundTrip() {
        Appointment saved = appointments.saveAndFlush(appointment(LocalTime.of(11, 0), AppointmentStatus.CONFIRMED));
        entityManager.clear();

        assertThat(appointments.findById(saved.getId())).get()
                .returns(AppointmentStatus.CONFIRMED, Appointment::getStatus)
                .returns(UrgencyLevel.URGENT, Appointment::getUrgency);
        assertThat(appointments.findByStatusAndAppointmentDateBetween(AppointmentStatus.CONFIRMED, DATE, DATE))
                .extracting(Appointment::getId).containsExactly(saved.getId());
    }

    private static Appointment appointment(LocalTime start, AppointmentStatus status) {
        return Appointment.builder()
                .patientId(1L).dentistId(DENTIST_ID).clinicId(3L)
                .appointmentDate(DATE).startTime(start).endTime(start.plusMinutes(30))
                .status(status).urgency(UrgencyLevel.URGENT)
                .build();
    }
}
