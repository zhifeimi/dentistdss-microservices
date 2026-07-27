package press.mizhifei.dentist.clinicalrecords.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinicalrecords.model.ClinicalNote;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClinicalNoteRepository extends JpaRepository<ClinicalNote, Long> {

    Optional<ClinicalNote> findByIdAndPatientIdAndIsDraftFalse(Long id, Long patientId);

    Optional<ClinicalNote> findByIdAndDentistIdAndClinicId(Long id, Long dentistId, Long clinicId);

    List<ClinicalNote> findByClinicIdOrderByCreatedAtDesc(Long clinicId);

    List<ClinicalNote> findByPatientIdOrderByCreatedAtDesc(Long patientId);

    List<ClinicalNote> findByPatientIdAndCategoryOrderByCreatedAtDesc(Long patientId, String category);

    List<ClinicalNote> findByPatientIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
            Long patientId, Long dentistId, Long clinicId);

    List<ClinicalNote> findByDentistIdAndClinicIdOrderByCreatedAtDesc(Long dentistId, Long clinicId);

    List<ClinicalNote> findByDentistIdOrderByCreatedAtDesc(Long dentistId);

    Optional<ClinicalNote> findByAppointmentId(Long appointmentId);

    Optional<ClinicalNote> findByAppointmentIdAndPatientIdAndIsDraftFalse(Long appointmentId, Long patientId);

    Optional<ClinicalNote> findByAppointmentIdAndDentistIdAndClinicId(
            Long appointmentId, Long dentistId, Long clinicId);

    List<ClinicalNote> findByVisitIdOrderByCreatedAtDesc(Long visitId);

    List<ClinicalNote> findByVisitIdAndPatientIdAndIsDraftFalseOrderByCreatedAtDesc(Long visitId, Long patientId);

    List<ClinicalNote> findByVisitIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
            Long visitId, Long dentistId, Long clinicId);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.patientId = :patientId "
            + "AND cn.isDraft = false ORDER BY cn.createdAt DESC")
    List<ClinicalNote> findSignedNotesByPatientId(@Param("patientId") Long patientId);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.patientId = :patientId "
            + "AND cn.dentistId = :dentistId AND cn.clinicId = :clinicId "
            + "AND cn.isDraft = false ORDER BY cn.createdAt DESC")
    List<ClinicalNote> findSignedNotesByPatientIdAndDentistIdAndClinicId(
            @Param("patientId") Long patientId,
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.dentistId = :dentistId "
            + "AND cn.clinicId = :clinicId AND cn.isDraft = true ORDER BY cn.updatedAt DESC")
    List<ClinicalNote> findDraftNotesByDentistIdAndClinicId(
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.dentistId = :dentistId "
            + "AND cn.isDraft = true ORDER BY cn.updatedAt DESC")
    List<ClinicalNote> findDraftNotesByDentistId(@Param("dentistId") Long dentistId);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.patientId = :patientId "
            + "AND cn.category = :category AND cn.isDraft = false ORDER BY cn.createdAt DESC")
    List<ClinicalNote> findSignedNotesByPatientIdAndCategory(
            @Param("patientId") Long patientId,
            @Param("category") String category);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.patientId = :patientId "
            + "AND cn.dentistId = :dentistId AND cn.clinicId = :clinicId "
            + "AND cn.category = :category ORDER BY cn.createdAt DESC")
    List<ClinicalNote> findByPatientIdAndDentistIdAndClinicIdAndCategory(
            @Param("patientId") Long patientId,
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId,
            @Param("category") String category);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.parentNoteId = :parentNoteId "
            + "ORDER BY cn.version DESC")
    List<ClinicalNote> findNoteVersions(@Param("parentNoteId") Long parentNoteId);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.parentNoteId = :parentNoteId "
            + "AND cn.patientId = :patientId AND cn.isDraft = false ORDER BY cn.version DESC")
    List<ClinicalNote> findSignedNoteVersionsByPatientId(
            @Param("parentNoteId") Long parentNoteId,
            @Param("patientId") Long patientId);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.parentNoteId = :parentNoteId "
            + "AND cn.dentistId = :dentistId AND cn.clinicId = :clinicId ORDER BY cn.version DESC")
    List<ClinicalNote> findNoteVersionsByDentistIdAndClinicId(
            @Param("parentNoteId") Long parentNoteId,
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.patientId = :patientId "
            + "AND (cn.chiefComplaint ILIKE %:searchTerm% "
            + "OR cn.diagnosis ILIKE %:searchTerm% "
            + "OR cn.treatmentPerformed ILIKE %:searchTerm%) ORDER BY cn.createdAt DESC")
    List<ClinicalNote> searchNotesByPatient(
            @Param("patientId") Long patientId,
            @Param("searchTerm") String searchTerm);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.patientId = :patientId "
            + "AND cn.isDraft = false AND (cn.chiefComplaint ILIKE %:searchTerm% "
            + "OR cn.diagnosis ILIKE %:searchTerm% "
            + "OR cn.treatmentPerformed ILIKE %:searchTerm%) ORDER BY cn.createdAt DESC")
    List<ClinicalNote> searchSignedNotesByPatient(
            @Param("patientId") Long patientId,
            @Param("searchTerm") String searchTerm);

    @Query("SELECT cn FROM ClinicalNote cn WHERE cn.patientId = :patientId "
            + "AND cn.dentistId = :dentistId AND cn.clinicId = :clinicId "
            + "AND (cn.chiefComplaint ILIKE %:searchTerm% "
            + "OR cn.diagnosis ILIKE %:searchTerm% "
            + "OR cn.treatmentPerformed ILIKE %:searchTerm%) ORDER BY cn.createdAt DESC")
    List<ClinicalNote> searchNotesByPatientAndDentistIdAndClinicId(
            @Param("patientId") Long patientId,
            @Param("dentistId") Long dentistId,
            @Param("clinicId") Long clinicId,
            @Param("searchTerm") String searchTerm);
}
