package press.mizhifei.dentist.notification.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import press.mizhifei.dentist.notification.dto.ApiResponse;
import press.mizhifei.dentist.notification.dto.NotificationRequest;
import press.mizhifei.dentist.notification.dto.NotificationResponse;
import press.mizhifei.dentist.notification.service.NotificationService;
import press.mizhifei.dentist.security.AuthenticatedUser;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/send")
    @PreAuthorize("hasAuthority('SERVICE_NOTIFICATION_SEND')")
    public Mono<ResponseEntity<ApiResponse<NotificationResponse>>> sendNotification(
            @Valid @RequestBody NotificationRequest request) {
        return Mono.fromCallable(() -> {
                    NotificationResponse response = notificationService.createNotification(request);
                    return ResponseEntity.ok(ApiResponse.success(response));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<ApiResponse<List<NotificationResponse>>>> getUserNotifications(
            @PathVariable Long userId,
            @AuthenticationPrincipal Jwt jwt) {
        long authenticatedUserId = requireSelf(userId, jwt);
        return Mono.fromCallable(() -> {
                    List<NotificationResponse> notifications =
                            notificationService.getUserNotifications(userId, authenticatedUserId);
                    return ResponseEntity.ok(ApiResponse.success(notifications));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @RequestMapping(
            path = "/{id}/read",
            method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<ApiResponse<NotificationResponse>>> markAsRead(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        long authenticatedUserId = currentUserId(jwt);
        return Mono.fromCallable(() -> {
                    NotificationResponse response =
                            notificationService.markAsRead(id, authenticatedUserId);
                    return ResponseEntity.ok(ApiResponse.success(response));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/user/{userId}/unread-count")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<ApiResponse<Map<String, Long>>>> getUnreadCount(
            @PathVariable Long userId,
            @AuthenticationPrincipal Jwt jwt) {
        long authenticatedUserId = requireSelf(userId, jwt);
        return Mono.fromCallable(() -> {
                    long count = notificationService.getUnreadCount(userId, authenticatedUserId);
                    return ResponseEntity.ok(
                            ApiResponse.success(Map.of("unreadCount", count)));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private long requireSelf(Long requestedUserId, Jwt jwt) {
        long authenticatedUserId = currentUserId(jwt);
        if (!requestedUserId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("Notification access is limited to the current user");
        }
        return authenticatedUserId;
    }

    private long currentUserId(Jwt jwt) {
        try {
            return AuthenticatedUser.from(jwt).requiredNumericUserId();
        } catch (IllegalStateException ex) {
            throw new AccessDeniedException("Authenticated subject is invalid", ex);
        }
    }
}
