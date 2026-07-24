package press.mizhifei.dentist.appointment.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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

    @GetMapping("/{userId}/name")
    String getUserFullName(@PathVariable("userId") Long userId);
}
