package press.mizhifei.dentist.clinic.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import press.mizhifei.dentist.clinic.dto.UserResponse;

import java.util.List;

/**
 * Feign client for user-profile-service. The caller's bearer token is relayed
 * by {@link UserProfileServiceFeignConfiguration}; user-profile-service
 * requires a verified user JWT on every route.
 */
@FeignClient(
        name = "user-profile-service",
        path = "/user",
        configuration = UserProfileServiceFeignConfiguration.class)
public interface UserProfileServiceClient {

    @GetMapping("/clinic/{clinicId}/dentists")
    List<UserResponse> getClinicDentists(@PathVariable("clinicId") Long clinicId);
}
