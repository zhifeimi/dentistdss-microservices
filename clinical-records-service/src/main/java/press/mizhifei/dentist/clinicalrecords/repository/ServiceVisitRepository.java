package press.mizhifei.dentist.clinicalrecords.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinicalrecords.model.ServiceVisit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceVisitRepository extends JpaRepository<ServiceVisit, Long> {

    Optional<ServiceVisit> findByIdAndPatientId(Long id, Long patientId);

    Optional<ServiceVisit> findByIdAndDentistIdAndClinicId(Long id, Long dentistId, Long clinicId);

    List<ServiceVisit> findByPatientIdOrderByVisitDateDesc(Long patientId);

    List<ServiceVisit> findByPatientIdAndDentistIdAndClinicIdOrderByVisitDateDesc(
            Long patientId, Long dentistId, Long clinicId);

    List<ServiceVisit> findByDentistIdAndClinicIdOrderByVisitDateDesc(Long dentistId, Long clinicId);

    List<ServiceVisit> findByDentistIdOrderByVisitDateDesc(Long dentistId);

    List<ServiceVisit> findByClinicIdOrderByVisitDateDesc(Long clinicId);

    Optional<ServiceVisit> findByAppointmentId(Long appointmentId);

    Optional<ServiceVisit> findByAppointmentIdAndPatientId(Long appointmentId, Long patientId);

    Optional<ServiceVisit> findByAppointmentIdAndDentistIdAndClinicId(
            Long appointmentId, Long dentistId, Long clinicId);

    @Query("SELECT sv FROM ServiceVisit sv WHERE sv.patientId = :patientId "
            + "AND sv.visitDate BETWEEN :startDate AND :endDate ORDER BY sv.visitDate DESC")
    List<ServiceVisit> findByPatientIdAndDateRange(
            @Param("patientId") Long patientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT sv FROM ServiceVisit sv WHERE sv.patientId = :patientId "
            + "AND sv.dentistId = :dentistId AND sv.clinicId = :clinicId "
            + "AND sv.visitDate BETWEEN :startDate AND :endDate ORDER BY sv.visitDate DESC")
    List<ServiceVisit> findByPatientIdAndDentistIdAndClinicIdAndDateRange(
            @Param("patientId") Long patientId,
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT sv FROM ServiceVisit sv WHERE sv.clinicId = :clinicId "
            + "AND sv.status = :status ORDER BY sv.visitDate DESC")
    List<ServiceVisit> findByClinicIdAndStatus(
            @Param("clinicId") Long clinicId,
            @Param("status") String status);
}
