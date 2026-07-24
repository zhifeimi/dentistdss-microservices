package press.mizhifei.dentist.notification.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import press.mizhifei.dentist.notification.dto.EmailRequest;
import press.mizhifei.dentist.notification.dto.NotificationEmailRequest;
import press.mizhifei.dentist.notification.dto.ProcessingReminderEmailRequest;
import press.mizhifei.dentist.notification.dto.SystemAdminApprovalEmailRequest;
import press.mizhifei.dentist.notification.dto.VerificationEmailRequest;
import press.mizhifei.dentist.notification.service.EmailService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Transactional email endpoints, reachable only with a verified service
 * credential holding the {@code notification:email} scope (currently
 * auth-service). Reactive method security requires Publisher return types,
 * so each handler wraps the blocking mail send on the bounded-elastic
 * scheduler — same pattern as {@link NotificationController}.
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Slf4j
@RestController
@RequestMapping("/notification/email")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService emailService;

    @PostMapping("/send")
    @PreAuthorize("hasAuthority('SERVICE_NOTIFICATION_EMAIL')")
    public Mono<ResponseEntity<String>> sendEmail(@RequestBody EmailRequest request) {
        return Mono.fromCallable(() -> {
                    try {
                        emailService.sendEmail(request.getTo(), request.getSubject(), request.getBody(), request.isHtml());
                        return ResponseEntity.ok("Email sent successfully");
                    } catch (Exception e) {
                        log.error("Failed to send email: {}", e.getMessage());
                        return ResponseEntity.<String>internalServerError().body("Failed to send email: " + e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/verification")
    @PreAuthorize("hasAuthority('SERVICE_NOTIFICATION_EMAIL')")
    public Mono<ResponseEntity<String>> sendVerificationEmail(@RequestBody VerificationEmailRequest request) {
        return Mono.fromCallable(() -> {
                    try {
                        if ("token".equals(request.getType())) {
                            emailService.sendVerificationEmail(request.getTo(), request.getVerificationValue());
                        } else {
                            emailService.sendVerificationCode(request.getTo(), request.getVerificationValue());
                        }
                        return ResponseEntity.ok("Verification email sent successfully");
                    } catch (Exception e) {
                        log.error("Failed to send verification email: {}", e.getMessage());
                        return ResponseEntity.<String>internalServerError().body("Failed to send verification email: " + e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/processing-reminder")
    @PreAuthorize("hasAuthority('SERVICE_NOTIFICATION_EMAIL')")
    public Mono<ResponseEntity<String>> sendProcessingReminderEmail(@RequestBody ProcessingReminderEmailRequest request) {
        return Mono.fromCallable(() -> {
                    try {
                        emailService.sendProcessingReminderEmail(
                                request.getClinicAdminEmail(),
                                request.getClinicName(),
                                request.getFirstName(),
                                request.getLastName(),
                                request.getEmail(),
                                request.getRole()
                        );
                        return ResponseEntity.ok("Processing reminder email sent successfully");
                    } catch (Exception e) {
                        log.error("Failed to send processing reminder email: {}", e.getMessage());
                        return ResponseEntity.<String>internalServerError().body("Failed to send processing reminder email: " + e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/system-admin-approval")
    @PreAuthorize("hasAuthority('SERVICE_NOTIFICATION_EMAIL')")
    public Mono<ResponseEntity<String>> sendSystemAdminApprovalEmail(@RequestBody SystemAdminApprovalEmailRequest request) {
        return Mono.fromCallable(() -> {
                    try {
                        emailService.sendSystemAdminApprovalEmail(
                                request.getSystemAdminEmail(),
                                request.getFirstName(),
                                request.getLastName(),
                                request.getEmail(),
                                request.getClinicName(),
                                request.getAddress(),
                                request.getCity(),
                                request.getState(),
                                request.getZipCode(),
                                request.getCountry(),
                                request.getPhoneNumber(),
                                request.getBusinessEmail()
                        );
                        return ResponseEntity.ok("System admin approval email sent successfully");
                    } catch (Exception e) {
                        log.error("Failed to send system admin approval email: {}", e.getMessage());
                        return ResponseEntity.<String>internalServerError().body("Failed to send system admin approval email: " + e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/notification")
    @PreAuthorize("hasAuthority('SERVICE_NOTIFICATION_EMAIL')")
    public Mono<ResponseEntity<String>> sendNotificationEmail(@RequestBody NotificationEmailRequest request) {
        return Mono.fromCallable(() -> {
                    try {
                        emailService.sendNotificationEmail(request.getTo(), request.getTemplateName(), request.getVariables());
                        return ResponseEntity.ok("Notification email sent successfully");
                    } catch (Exception e) {
                        log.error("Failed to send notification email: {}", e.getMessage());
                        return ResponseEntity.<String>internalServerError().body("Failed to send notification email: " + e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
