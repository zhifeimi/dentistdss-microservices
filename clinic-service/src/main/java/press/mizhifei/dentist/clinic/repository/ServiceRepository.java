package press.mizhifei.dentist.clinic.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinic.model.Service;

import java.util.List;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Repository
public interface ServiceRepository extends JpaRepository<Service, Integer> {
    
    List<Service> findByClinicIdAndIsActiveTrue(Long clinicId);
    
    List<Service> findByCategoryAndIsActiveTrue(String category);
    
    List<Service> findByClinicIdAndCategoryAndIsActiveTrue(Long clinicId, String category);
    
    List<Service> findByClinicId(Long clinicId);
    
    @Query("select s from Service s where s.isActive = true and s.clinicId in "
            + "(select c.id from Clinic c where " + ClinicRepository.PUBLICLY_LISTED + ") "
            + "order by s.clinicId, s.id")
    List<Service> findActiveForPubliclyListedClinics();

    @Query("SELECT DISTINCT s.category FROM Service s WHERE s.clinicId = :clinicId AND s.category IS NOT NULL")
    List<String> findDistinctCategoriesByClinicId(@Param("clinicId") Long clinicId);
} 