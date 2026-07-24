package press.mizhifei.dentist.clinicalrecords.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinicalrecords.model.TreatmentPlanItem;

import java.util.List;
import java.util.Optional;

@Repository
public interface TreatmentPlanItemRepository extends JpaRepository<TreatmentPlanItem, Integer> {

    Optional<TreatmentPlanItem> findByIdAndTreatmentPlanId(Integer id, Integer treatmentPlanId);

    List<TreatmentPlanItem> findByTreatmentPlanIdOrderBySequenceOrder(Integer treatmentPlanId);

    List<TreatmentPlanItem> findByTreatmentPlanIdAndStatus(Integer treatmentPlanId, String status);
}
