package press.mizhifei.dentist.clinic.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinic.model.ClinicSlugHistory;

import java.util.List;

@Repository
public interface ClinicSlugHistoryRepository extends JpaRepository<ClinicSlugHistory, String> {

    List<ClinicSlugHistory> findByClinicIdOrderByRetiredAtDescSlugAsc(Long clinicId);

    @Query("select h from ClinicSlugHistory h where h.clinicId in "
            + "(select c.id from Clinic c where " + ClinicRepository.PUBLICLY_LISTED + ") "
            + "order by h.clinicId, h.retiredAt desc, h.slug")
    List<ClinicSlugHistory> findForPubliclyListedClinics();
}
