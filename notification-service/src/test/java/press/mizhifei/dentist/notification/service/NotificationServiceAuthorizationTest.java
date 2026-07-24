package press.mizhifei.dentist.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import press.mizhifei.dentist.notification.model.Notification;
import press.mizhifei.dentist.notification.model.NotificationStatus;
import press.mizhifei.dentist.notification.model.NotificationType;
import press.mizhifei.dentist.notification.repository.NotificationRepository;
import press.mizhifei.dentist.notification.repository.NotificationTemplateRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationServiceAuthorizationTest {

    private NotificationRepository notificationRepository;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        notificationService = new NotificationService(
                notificationRepository,
                mock(NotificationTemplateRepository.class),
                mock(EmailService.class),
                mock(ObjectMapper.class));
    }

    @Test
    void rejectsCrossUserNotificationListBeforeRepositoryAccess() {
        assertThrows(
                AccessDeniedException.class,
                () -> notificationService.getUserNotifications(42L, 41L));

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void loadsOnlyAuthenticatedUsersNotifications() {
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(42L))
                .thenReturn(List.of());

        assertEquals(
                List.of(),
                notificationService.getUserNotifications(42L, 42L));

        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(42L);
    }

    @Test
    void rejectsNotificationMutationWhenIdIsNotOwnedByCurrentUser() {
        when(notificationRepository.findByIdAndUserId(100L, 42L))
                .thenReturn(Optional.empty());

        assertThrows(
                AccessDeniedException.class,
                () -> notificationService.markAsRead(100L, 42L));

        verify(notificationRepository).findByIdAndUserId(100L, 42L);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void marksOnlyOwnedNotificationAsRead() {
        Notification notification = Notification.builder()
                .id(100L)
                .userId(42L)
                .type(NotificationType.IN_APP)
                .body("Appointment updated")
                .status(NotificationStatus.SENT)
                .build();
        when(notificationRepository.findByIdAndUserId(100L, 42L))
                .thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = notificationService.markAsRead(100L, 42L);

        assertEquals("READ", response.getStatus());
        assertNotNull(response.getReadAt());
        verify(notificationRepository).save(notification);
    }

    @Test
    void rejectsCrossUserUnreadCountBeforeRepositoryAccess() {
        assertThrows(
                AccessDeniedException.class,
                () -> notificationService.getUnreadCount(42L, 41L));

        verifyNoInteractions(notificationRepository);
    }
}
