package press.mizhifei.dentist.clinicalrecords.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinicalrecords.model.DentalImage;

import java.util.List;
import java.util.Optional;

@Repository
public interface DentalImageRepository extends JpaRepository<DentalImage, Long> {

    Optional<DentalImage> findByIdAndPatientId(Long id, Long patientId);

    Optional<DentalImage> findByIdAndDentistIdAndClinicId(Long id, Long dentistId, Long clinicId);

    List<DentalImage> findByPatientIdOrderByCreatedAtDesc(Long patientId);

    List<DentalImage> findByPatientIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
            Long patientId, Long dentistId, Long clinicId);

    List<DentalImage> findByClinicalNoteIdOrderByCreatedAtDesc(Long clinicalNoteId);

    List<DentalImage> findByClinicalNoteIdAndPatientIdOrderByCreatedAtDesc(Long clinicalNoteId, Long patientId);

    List<DentalImage> findByClinicalNoteIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
            Long clinicalNoteId, Long dentistId, Long clinicId);

    List<DentalImage> findByVisitIdOrderByCreatedAtDesc(Long visitId);

    List<DentalImage> findByVisitIdAndPatientIdOrderByCreatedAtDesc(Long visitId, Long patientId);

    List<DentalImage> findByVisitIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
            Long visitId, Long dentistId, Long clinicId);

    List<DentalImage> findByPatientIdAndImageTypeOrderByCreatedAtDesc(Long patientId, String imageType);

    List<DentalImage> findByPatientIdAndDentistIdAndClinicIdAndImageTypeOrderByCreatedAtDesc(
            Long patientId, Long dentistId, Long clinicId, String imageType);

    @Query("SELECT di FROM DentalImage di WHERE di.patientId = :patientId "
            + "AND di.toothNumber = :toothNumber ORDER BY di.createdAt DESC")
    List<DentalImage> findByPatientIdAndToothNumber(
            @Param("patientId") Long patientId,
            @Param("toothNumber") String toothNumber);

    @Query("SELECT di FROM DentalImage di WHERE di.patientId = :patientId "
            + "AND di.dentistId = :dentistId AND di.clinicId = :clinicId "
            + "AND di.toothNumber = :toothNumber ORDER BY di.createdAt DESC")
    List<DentalImage> findByPatientIdAndDentistIdAndClinicIdAndToothNumber(
            @Param("patientId") Long patientId,
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId,
            @Param("toothNumber") String toothNumber);

    @Query("SELECT di FROM DentalImage di WHERE di.patientId = :patientId "
            + "AND di.tags ILIKE %:tag% ORDER BY di.createdAt DESC")
    List<DentalImage> findByPatientIdAndTag(
            @Param("patientId") Long patientId,
            @Param("tag") String tag);

    @Query("SELECT di FROM DentalImage di WHERE di.patientId = :patientId "
            + "AND di.dentistId = :dentistId AND di.clinicId = :clinicId "
            + "AND di.tags ILIKE %:tag% ORDER BY di.createdAt DESC")
    List<DentalImage> findByPatientIdAndDentistIdAndClinicIdAndTag(
            @Param("patientId") Long patientId,
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId,
            @Param("tag") String tag);
}
