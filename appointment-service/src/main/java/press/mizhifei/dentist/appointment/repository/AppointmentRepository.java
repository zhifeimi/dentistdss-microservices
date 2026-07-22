package press.mizhifei.dentist.appointment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.appointment.model.Appointment;
import press.mizhifei.dentist.appointment.model.AppointmentStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findByIdAndPatientId(Long id, Long patientId);

    Optional<Appointment> findByIdAndDentistId(Long id, Long dentistId);

    Optional<Appointment> findByIdAndClinicId(Long id, Long clinicId);

    List<Appointment> findByPatientIdOrderByAppointmentDateDescStartTimeDesc(Long patientId);

    List<Appointment> findByPatientIdAndDentistIdOrderByAppointmentDateDescStartTimeDesc(
            Long patientId,
            Long dentistId);

    List<Appointment> findByPatientIdAndClinicIdOrderByAppointmentDateDescStartTimeDesc(
            Long patientId,
            Long clinicId);

    List<Appointment> findByDentistIdAndAppointmentDateOrderByStartTime(
            Long dentistId,
            LocalDate date);

    List<Appointment> findByDentistIdAndClinicIdAndAppointmentDateOrderByStartTime(
            Long dentistId,
            Long clinicId,
            LocalDate date);

    List<Appointment> findByClinicIdAndAppointmentDateOrderByStartTime(
            Long clinicId,
            LocalDate date);

    @Query("SELECT a FROM Appointment a WHERE a.dentistId = :dentistId "
            + "AND a.appointmentDate = :date "
            + "AND a.status NOT IN :nonBlockingStatuses "
            + "AND ((a.startTime <= :startTime AND a.endTime > :startTime) "
            + "OR (a.startTime < :endTime AND a.endTime >= :endTime) "
            + "OR (a.startTime >= :startTime AND a.endTime <= :endTime))")
    List<Appointment> findConflictingAppointments(
            @Param("dentistId") Long dentistId,
            @Param("date") LocalDate date,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("nonBlockingStatuses") List<AppointmentStatus> nonBlockingStatuses);

    /**
     * Serializes appointment writes for a dentist for the current transaction.
     * PostgreSQL releases the advisory lock automatically at transaction end.
     */
    @Query(nativeQuery = true, value = "SELECT 1 FROM pg_advisory_xact_lock(:dentistId)")
    Integer acquireDentistScheduleLock(@Param("dentistId") Long dentistId);

    @Query("SELECT a FROM Appointment a WHERE a.dentistId = :dentistId "
            + "AND a.appointmentDate = :date "
            + "AND a.status NOT IN :nonBlockingStatuses "
            + "ORDER BY a.startTime")
    List<Appointment> findSlotBlockingAppointments(
            @Param("dentistId") Long dentistId,
            @Param("date") LocalDate date,
            @Param("nonBlockingStatuses") List<AppointmentStatus> nonBlockingStatuses);

    List<Appointment> findByStatusAndAppointmentDateBetween(
            AppointmentStatus status,
            LocalDate startDate,
            LocalDate endDate);

    @Query("SELECT a FROM Appointment a WHERE a.appointmentDate = :tomorrow "
            + "AND a.status = 'CONFIRMED'")
    List<Appointment> findConfirmedAppointmentsForDate(
            @Param("tomorrow") LocalDate tomorrow);

    @Query("SELECT a FROM Appointment a WHERE a.patientId = :patientId "
            + "AND a.clinicId = :clinicId "
            + "AND a.status = 'COMPLETED' "
            + "AND a.appointmentDate < :currentDate "
            + "ORDER BY a.appointmentDate DESC, a.startTime DESC")
    List<Appointment> findLastCompletedAppointmentByPatientAndClinic(
            @Param("patientId") Long patientId,
            @Param("clinicId") Long clinicId,
            @Param("currentDate") LocalDate currentDate);

    @Query("SELECT a FROM Appointment a WHERE a.patientId = :patientId "
            + "AND a.clinicId = :clinicId "
            + "AND a.dentistId = :dentistId "
            + "AND a.status = 'COMPLETED' "
            + "AND a.appointmentDate < :currentDate "
            + "ORDER BY a.appointmentDate DESC, a.startTime DESC")
    List<Appointment> findLastCompletedAppointmentByPatientClinicAndDentist(
            @Param("patientId") Long patientId,
            @Param("clinicId") Long clinicId,
            @Param("dentistId") Long dentistId,
            @Param("currentDate") LocalDate currentDate);

    @Query("SELECT a FROM Appointment a WHERE a.patientId = :patientId "
            + "AND a.clinicId = :clinicId "
            + "AND a.status IN ('REQUESTED', 'CONFIRMED', 'RESCHEDULED') "
            + "AND (a.appointmentDate > :currentDate OR "
            + "(a.appointmentDate = :currentDate AND a.startTime > :currentTime)) "
            + "ORDER BY a.appointmentDate ASC, a.startTime ASC")
    List<Appointment> findNextUpcomingAppointmentByPatientAndClinic(
            @Param("patientId") Long patientId,
            @Param("clinicId") Long clinicId,
            @Param("currentDate") LocalDate currentDate,
            @Param("currentTime") LocalTime currentTime);

    @Query("SELECT a FROM Appointment a WHERE a.patientId = :patientId "
            + "AND a.clinicId = :clinicId "
            + "AND a.dentistId = :dentistId "
            + "AND a.status IN ('REQUESTED', 'CONFIRMED', 'RESCHEDULED') "
            + "AND (a.appointmentDate > :currentDate OR "
            + "(a.appointmentDate = :currentDate AND a.startTime > :currentTime)) "
            + "ORDER BY a.appointmentDate ASC, a.startTime ASC")
    List<Appointment> findNextUpcomingAppointmentByPatientClinicAndDentist(
            @Param("patientId") Long patientId,
            @Param("clinicId") Long clinicId,
            @Param("dentistId") Long dentistId,
            @Param("currentDate") LocalDate currentDate,
            @Param("currentTime") LocalTime currentTime);

    @Query("SELECT DISTINCT a.patientId FROM Appointment a WHERE a.clinicId = :clinicId")
    List<Long> findDistinctPatientIdsByClinicId(@Param("clinicId") Long clinicId);

    @Query(nativeQuery = true, value = "INSERT INTO appointments "
            + "(id, patient_id, dentist_id, clinic_id, service_id, appointment_date, start_time, end_time, "
            + "status, reason_for_visit, symptoms, urgency, ai_triage_notes, notes, created_by, created_at, updated_at) "
            + "VALUES (nextval('appointment_id_seq'), :patientId, :dentistId, :clinicId, :serviceId, :appointmentDate, :startTime, :endTime, "
            + "CAST(:status AS appointment_status), :reasonForVisit, :symptoms, CAST(:urgency AS urgency_level), "
            + ":aiTriageNotes, :notes, :createdBy, NOW(), NOW()) RETURNING *")
    Appointment saveWithCasting(
            @Param("patientId") Long patientId,
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId,
            @Param("serviceId") Integer serviceId,
            @Param("appointmentDate") LocalDate appointmentDate,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("status") String status,
            @Param("reasonForVisit") String reasonForVisit,
            @Param("symptoms") String symptoms,
            @Param("urgency") String urgency,
            @Param("aiTriageNotes") String aiTriageNotes,
            @Param("notes") String notes,
            @Param("createdBy") Long createdBy);

    @Query(nativeQuery = true, value = "UPDATE appointments SET "
            + "status = CAST('CONFIRMED' AS appointment_status), "
            + "confirmed_by = :confirmedBy, updated_at = NOW() "
            + "WHERE id = :id AND status = CAST('REQUESTED' AS appointment_status) RETURNING *")
    Appointment confirmAppointment(
            @Param("id") Long id,
            @Param("confirmedBy") Long confirmedBy);

    @Query(nativeQuery = true, value = "UPDATE appointments SET "
            + "status = CAST('CANCELLED' AS appointment_status), "
            + "cancellation_reason = :cancellationReason, "
            + "cancelled_by = :cancelledBy, updated_at = NOW() "
            + "WHERE id = :id AND status IN ("
            + "CAST('REQUESTED' AS appointment_status), "
            + "CAST('CONFIRMED' AS appointment_status), "
            + "CAST('RESCHEDULED' AS appointment_status)) RETURNING *")
    Appointment cancelAppointment(
            @Param("id") Long id,
            @Param("cancellationReason") String cancellationReason,
            @Param("cancelledBy") Long cancelledBy);

    @Query(nativeQuery = true, value = "UPDATE appointments SET "
            + "appointment_date = :appointmentDate, start_time = :startTime, "
            + "end_time = :endTime, status = CAST('RESCHEDULED' AS appointment_status), "
            + "updated_at = NOW() WHERE id = :id AND status IN ("
            + "CAST('REQUESTED' AS appointment_status), "
            + "CAST('CONFIRMED' AS appointment_status), "
            + "CAST('RESCHEDULED' AS appointment_status)) RETURNING *")
    Appointment rescheduleAppointment(
            @Param("id") Long id,
            @Param("appointmentDate") LocalDate appointmentDate,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime);

    @Query(nativeQuery = true, value = "UPDATE appointments SET "
            + "status = CAST('COMPLETED' AS appointment_status), updated_at = NOW() "
            + "WHERE id = :id AND status = CAST('CONFIRMED' AS appointment_status) RETURNING *")
    Appointment completeAppointment(@Param("id") Long id);

    @Query(nativeQuery = true, value = "UPDATE appointments SET "
            + "status = CAST('NO_SHOW' AS appointment_status), updated_at = NOW() "
            + "WHERE id = :id AND status IN ("
            + "CAST('CONFIRMED' AS appointment_status), "
            + "CAST('RESCHEDULED' AS appointment_status)) RETURNING *")
    Appointment markNoShow(@Param("id") Long id);
}
