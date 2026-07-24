package press.mizhifei.dentist.clinic.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import press.mizhifei.dentist.clinic.dto.ApiResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@FeignClient(
        name = "appointment-service",
        path = "/appointment",
        configuration = AppointmentServiceFeignConfiguration.class)
public interface AppointmentServiceClient {
    
    @GetMapping("/patient/{patientId}/clinic/{clinicId}/last-completed")
    ApiResponse<List<AppointmentResponse>> getLastCompletedAppointment(
            @PathVariable("patientId") Long patientId,
            @PathVariable("clinicId") Long clinicId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentDate);
    
    @GetMapping("/patient/{patientId}/clinic/{clinicId}/next-upcoming")
    ApiResponse<List<AppointmentResponse>> getNextUpcomingAppointment(
            @PathVariable("patientId") Long patientId,
            @PathVariable("clinicId") Long clinicId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime currentTime);
    
    @GetMapping("/clinic/{clinicId}/patients")
    ApiResponse<List<Long>> getClinicPatientIds(@PathVariable("clinicId") Long clinicId);
}


