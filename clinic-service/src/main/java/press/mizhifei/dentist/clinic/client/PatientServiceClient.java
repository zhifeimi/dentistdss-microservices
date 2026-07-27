package press.mizhifei.dentist.clinic.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import press.mizhifei.dentist.clinic.dto.PatientResponse;

/**
 * Feign client for user-profile-service patient routes. The caller's bearer
 * token is relayed by {@link UserProfileServiceFeignConfiguration}.
 */
@FeignClient(
        name = "user-profile-service",
        path = "/patient",
        configuration = UserProfileServiceFeignConfiguration.class)
public interface PatientServiceClient {

    @GetMapping("/{id}")
    PatientResponse getPatientById(@PathVariable("id") Long patientId);
}
