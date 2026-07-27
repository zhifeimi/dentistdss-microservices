package press.mizhifei.dentist.auth.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import press.mizhifei.dentist.auth.dto.EmailRequest;
import press.mizhifei.dentist.auth.dto.NotificationEmailRequest;
import press.mizhifei.dentist.auth.dto.ProcessingReminderEmailRequest;
import press.mizhifei.dentist.auth.dto.SystemAdminApprovalEmailRequest;
import press.mizhifei.dentist.auth.dto.VerificationEmailRequest;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@FeignClient(name = "notification-service", path = "/notification/email",
        configuration = NotificationEmailFeignConfiguration.class)
public interface NotificationServiceClient {

    @PostMapping("/send")
    ResponseEntity<String> sendEmail(@RequestBody EmailRequest request);

    @PostMapping("/verification")
    ResponseEntity<String> sendVerificationEmail(@RequestBody VerificationEmailRequest request);

    @PostMapping("/processing-reminder")
    ResponseEntity<String> sendProcessingReminderEmail(@RequestBody ProcessingReminderEmailRequest request);

    @PostMapping("/system-admin-approval")
    ResponseEntity<String> sendSystemAdminApprovalEmail(@RequestBody SystemAdminApprovalEmailRequest request);

    @PostMapping("/notification")
    ResponseEntity<String> sendNotificationEmail(@RequestBody NotificationEmailRequest request);
}
