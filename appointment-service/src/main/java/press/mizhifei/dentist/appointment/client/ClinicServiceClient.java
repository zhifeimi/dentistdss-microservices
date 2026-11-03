package press.mizhifei.dentist.appointment.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import press.mizhifei.dentist.appointment.dto.ApiResponse;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@FeignClient(
        name = "clinic-service",
        path = "/clinic",
        configuration = ClinicServiceFeignConfiguration.class)
public interface ClinicServiceClient {
    
    @GetMapping("/service/{serviceId}")
    ApiResponse<ServiceResponse> getService(@PathVariable("serviceId") Integer serviceId);
    
    @GetMapping("/{clinicId}")
    ApiResponse<ClinicResponse> getClinic(@PathVariable("clinicId") Long clinicId);

    /** Holiday closure for one date; {@code date} is ISO {@code yyyy-MM-dd}. */
    @GetMapping("/{clinicId}/holidays/closure")
    ApiResponse<ClinicClosureResponse> getClosure(
            @PathVariable("clinicId") Long clinicId,
            @RequestParam("date") String date);
}


