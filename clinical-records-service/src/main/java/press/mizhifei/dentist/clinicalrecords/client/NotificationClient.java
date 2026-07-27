package press.mizhifei.dentist.clinicalrecords.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Sends treatment-plan notifications through notification-service. Each call
 * is authenticated with an audience-scoped service credential attached by
 * {@link NotificationSendFeignConfiguration}; the payload must satisfy
 * notification-service's {@code NotificationRequest} contract (a
 * {@code @NotNull} numeric {@code userId}, a {@code @NotNull} {@code type}
 * from EMAIL/SMS/PUSH/IN_APP, and a {@code @NotBlank} {@code body}).
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@FeignClient(
        name = "notification-service",
        path = "/notification",
        configuration = NotificationSendFeignConfiguration.class)
public interface NotificationClient {
    
    @PostMapping("/send")
    void sendNotification(@RequestBody Map<String, Object> notificationRequest);
}
