package press.mizhifei.dentist.clinicalrecords.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinicalrecords.model.TreatmentPlan;

import java.util.List;
import java.util.Optional;

@Repository
public interface TreatmentPlanRepository extends JpaRepository<TreatmentPlan, Integer> {

    Optional<TreatmentPlan> findByIdAndPatientId(Integer id, Long patientId);

    Optional<TreatmentPlan> findByIdAndDentistIdAndClinicId(Integer id, Long dentistId, Long clinicId);

    List<TreatmentPlan> findByPatientIdOrderByCreatedAtDesc(Long patientId);

    List<TreatmentPlan> findByPatientIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
            Long patientId, Long dentistId, Long clinicId);

    List<TreatmentPlan> findByDentistIdAndClinicIdOrderByCreatedAtDesc(Long dentistId, Long clinicId);

    List<TreatmentPlan> findByDentistIdOrderByCreatedAtDesc(Long dentistId);

    List<TreatmentPlan> findByPatientIdAndStatus(Long patientId, String status);

    List<TreatmentPlan> findByClinicIdAndStatus(Long clinicId, String status);

    @Query("SELECT tp FROM TreatmentPlan tp WHERE tp.parentPlanId = :parentPlanId "
            + "ORDER BY tp.version DESC")
    List<TreatmentPlan> findPlanVersions(@Param("parentPlanId") Integer parentPlanId);

    @Query("SELECT tp FROM TreatmentPlan tp WHERE tp.parentPlanId = :parentPlanId "
            + "AND tp.patientId = :patientId ORDER BY tp.version DESC")
    List<TreatmentPlan> findPlanVersionsByPatientId(
            @Param("parentPlanId") Integer parentPlanId,
            @Param("patientId") Long patientId);

    @Query("SELECT tp FROM TreatmentPlan tp WHERE tp.parentPlanId = :parentPlanId "
            + "AND tp.dentistId = :dentistId AND tp.clinicId = :clinicId ORDER BY tp.version DESC")
    List<TreatmentPlan> findPlanVersionsByDentistIdAndClinicId(
            @Param("parentPlanId") Integer parentPlanId,
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId);

    @Query("SELECT tp FROM TreatmentPlan tp WHERE tp.patientId = :patientId "
            + "AND tp.parentPlanId IS NULL ORDER BY tp.createdAt DESC")
    List<TreatmentPlan> findOriginalPlansByPatient(@Param("patientId") Long patientId);
}
