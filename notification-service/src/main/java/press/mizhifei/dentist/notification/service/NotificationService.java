package press.mizhifei.dentist.notification.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.notification.dto.NotificationRequest;
import press.mizhifei.dentist.notification.dto.NotificationResponse;
import press.mizhifei.dentist.notification.model.Notification;
import press.mizhifei.dentist.notification.model.NotificationStatus;
import press.mizhifei.dentist.notification.model.NotificationType;
import press.mizhifei.dentist.notification.model.NotificationTemplate;
import press.mizhifei.dentist.notification.repository.NotificationRepository;
import press.mizhifei.dentist.notification.repository.NotificationTemplateRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    
    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository templateRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;
    
    @Transactional
    public NotificationResponse createNotification(NotificationRequest request) {
        Notification notification;
        
        if (request.getTemplateName() != null) {
            // Use template
            NotificationTemplate template = templateRepository
                    .findByNameAndIsActiveTrue(request.getTemplateName())
                    .orElseThrow(() -> new IllegalArgumentException("Template not found: " + request.getTemplateName()));
            
            String body = processTemplate(template.getBodyTemplate(), request.getTemplateVariables());
            String subject = template.getSubject() != null ? 
                    processTemplate(template.getSubject(), request.getTemplateVariables()) : 
                    request.getSubject();
            
            notification = Notification.builder()
                    .userId(request.getUserId())
                    .templateId(template.getId())
                    .type(template.getType())
                    .subject(subject)
                    .body(body)
                    .scheduledFor(request.getScheduledFor())
                    .metadata(convertToJsonNode(request.getMetadata()))
                    .build();
        } else {
            // Direct notification
            notification = Notification.builder()
                    .userId(request.getUserId())
                    .type(NotificationType.valueOf(request.getType()))
                    .subject(request.getSubject())
                    .body(request.getBody())
                    .scheduledFor(request.getScheduledFor())
                    .metadata(convertToJsonNode(request.getMetadata()))
                    .build();
        }
        
        Notification saved = notificationRepository.save(notification);
        
        // Send immediately if not scheduled
        if (saved.getScheduledFor() == null || saved.getScheduledFor().isBefore(LocalDateTime.now())) {
            sendNotificationAsync(saved);
        }
        
        return toResponse(saved);
    }
    
    @Async
    @Transactional
    public void sendNotificationAsync(Notification notification) {
        try {
            switch (notification.getType()) {
                case EMAIL -> {
                    emailService.sendEmail(
                            notification.getUserId(),
                            notification.getSubject(),
                            notification.getBody()
                    );
                    notification.setStatus(NotificationStatus.SENT);
                    notification.setSentAt(LocalDateTime.now());
                }
                case SMS -> {
                    // TODO: Implement SMS service
                    log.info("SMS notification to user {}: {}", notification.getUserId(), notification.getBody());
                    notification.setStatus(NotificationStatus.SENT);
                    notification.setSentAt(LocalDateTime.now());
                }
                case PUSH -> {
                    // TODO: Implement push notification service
                    log.info("Push notification to user {}: {}", notification.getUserId(), notification.getBody());
                    notification.setStatus(NotificationStatus.SENT);
                    notification.setSentAt(LocalDateTime.now());
                }
                case IN_APP -> {
                    // In-app notifications are just stored and shown in the UI
                    notification.setStatus(NotificationStatus.SENT);
                    notification.setSentAt(LocalDateTime.now());
                }
            }
            notificationRepository.save(notification);
        } catch (Exception e) {
            log.error("Failed to send notification {}: {}", notification.getId(), e.getMessage());
            notification.setStatus(NotificationStatus.FAILED);
            notificationRepository.save(notification);
        }
    }
    
    @Transactional(readOnly = true)
    public List<NotificationResponse> getUserNotifications(
            Long userId,
            long authenticatedUserId) {
        requireSelf(userId, authenticatedUserId);
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public NotificationResponse markAsRead(
            Long notificationId,
            long authenticatedUserId) {
        Notification notification = notificationRepository
                .findByIdAndUserId(notificationId, authenticatedUserId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Notification access is limited to the owner"));

        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            if (notification.getStatus() == NotificationStatus.SENT) {
                notification.setStatus(NotificationStatus.READ);
            }
            notificationRepository.save(notification);
        }

        return toResponse(notification);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId, long authenticatedUserId) {
        requireSelf(userId, authenticatedUserId);
        return notificationRepository.countUnreadByUserId(userId);
    }
    
    @Transactional
    public void processScheduledNotifications() {
        LocalDateTime now = LocalDateTime.now();
        List<Notification> pendingNotifications = notificationRepository.findPendingNotifications(now);
        
        for (Notification notification : pendingNotifications) {
            sendNotificationAsync(notification);
        }
    }
    
    private void requireSelf(Long requestedUserId, long authenticatedUserId) {
        if (!requestedUserId.equals(authenticatedUserId)) {
            throw new AccessDeniedException(
                    "Notification access is limited to the current user");
        }
    }

    private String processTemplate(String template, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        
        String processed = template;
        Pattern pattern = Pattern.compile("\\{\\{(\\w+)\\}\\}");
        Matcher matcher = pattern.matcher(template);
        
        while (matcher.find()) {
            String variable = matcher.group(1);
            String value = variables.getOrDefault(variable, "");
            processed = processed.replace("{{" + variable + "}}", value);
        }
        
        return processed;
    }
    
    private ObjectNode convertToJsonNode(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return objectMapper.valueToTree(metadata);
    }
    
    private NotificationResponse toResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .userId(notification.getUserId())
                .type(notification.getType().toString())
                .subject(notification.getSubject())
                .body(notification.getBody())
                .status(notification.getStatus().toString())
                .scheduledFor(notification.getScheduledFor())
                .sentAt(notification.getSentAt())
                .readAt(notification.getReadAt())
                .metadata(notification.getMetadata())
                .createdAt(notification.getCreatedAt())
                .build();
    }
} 