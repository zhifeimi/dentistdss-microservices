package press.mizhifei.dentist.clinic.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinic.model.ClinicHoliday;

import java.util.List;

@Repository
public interface ClinicHolidayRepository extends JpaRepository<ClinicHoliday, Long> {

    List<ClinicHoliday> findByClinicIdOrderByHolidayDateAsc(Long clinicId);
}
