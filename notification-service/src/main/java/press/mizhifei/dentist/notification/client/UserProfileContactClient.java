package press.mizhifei.dentist.notification.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Contact lookups against user-profile-service, authenticated with a
 * {@code user:contact:read} service credential minted by this service
 * (see {@link UserProfileContactFeignConfiguration}). The earlier client
 * pointed at auth-service, which exposes no {@code /user/{id}/email}
 * endpoint, so every lookup failed into the development fallback.
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@FeignClient(
        name = "user-profile-service",
        path = "/user",
        configuration = UserProfileContactFeignConfiguration.class)
public interface UserProfileContactClient {

    @GetMapping("/{id}/email")
    String getUserEmail(@PathVariable("id") Long userId);
}
