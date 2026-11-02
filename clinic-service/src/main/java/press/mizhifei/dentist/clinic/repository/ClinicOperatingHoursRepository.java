package press.mizhifei.dentist.clinic.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinic.model.ClinicOperatingHours;

import java.util.List;

@Repository
public interface ClinicOperatingHoursRepository extends JpaRepository<ClinicOperatingHours, Long> {

    List<ClinicOperatingHours> findByClinicIdOrderByDayOfWeekAsc(Long clinicId);

    @Query("select h from ClinicOperatingHours h where h.clinicId in "
            + "(select c.id from Clinic c where " + ClinicRepository.PUBLICLY_LISTED + ") "
            + "order by h.clinicId, h.dayOfWeek")
    List<ClinicOperatingHours> findForPubliclyListedClinics();

    /**
     * Bulk delete so the following inserts never collide with the
     * (clinic_id, day_of_week) unique constraint inside one flush.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ClinicOperatingHours h where h.clinicId = :clinicId")
    int deleteByClinicId(@Param("clinicId") Long clinicId);
}
