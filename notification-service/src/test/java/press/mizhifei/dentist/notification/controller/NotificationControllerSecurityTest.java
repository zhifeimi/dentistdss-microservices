package press.mizhifei.dentist.notification.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import press.mizhifei.dentist.notification.config.NotificationSecurityConfig;
import press.mizhifei.dentist.notification.dto.NotificationResponse;
import press.mizhifei.dentist.notification.service.EmailService;
import press.mizhifei.dentist.notification.service.NotificationService;
import press.mizhifei.dentist.security.ReactiveJwtSecurityAutoConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@WebFluxTest({NotificationController.class, EmailController.class})
@ImportAutoConfiguration(ReactiveWebSecurityAutoConfiguration.class)
@Import({
        NotificationSecurityConfig.class,
        ReactiveJwtSecurityAutoConfiguration.class
})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.example/jwks",
        "jwt.issuer=https://issuer.example",
        "jwt.audience=dentistdss-api",
        "springdoc.api-docs.enabled=false",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class NotificationControllerSecurityTest {

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void usesNamedSharedReactiveJwtDecoderInsteadOfBootDefault() {
        assertTrue(applicationContext.containsBean("dentistDssAccessTokenReactiveJwtDecoder"));
        assertFalse(applicationContext.containsBean("reactiveJwtDecoder"));
        assertFalse(applicationContext.containsBean("jwtDecoder"));
    }

    @Test
    void rejectsNotificationReadWithoutBearerToken() {
        webTestClient.get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    @Test
    void forgedIdentityHeadersDoNotAuthenticateNotificationRead() {
        webTestClient.get()
                .uri("/notification/user/42")
                .header("X-User-ID", "42")
                .header("X-User-Roles", "SYSTEM_ADMIN")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectsCrossUserNotificationRead() {
        userClient("41")
                .get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    @Test
    void systemAdminCannotReadAnotherUsersNotifications() {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt
                        .subject("41")
                        .claim("roles", List.of("SYSTEM_ADMIN"))))
                .get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    @Test
    void allowsOwnerToReadNotifications() {
        when(notificationService.getUserNotifications(42L, 42L))
                .thenReturn(List.of());

        userClient("42")
                .get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isOk();

        verify(notificationService).getUserNotifications(42L, 42L);
    }

    @Test
    void allowsOwnerToReadUnreadCount() {
        when(notificationService.getUnreadCount(42L, 42L)).thenReturn(3L);

        userClient("42")
                .get()
                .uri("/notification/user/42/unread-count")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.dataObject.unreadCount").isEqualTo(3);

        verify(notificationService).getUnreadCount(42L, 42L);
    }

    @Test
    void allowsOwnerToMarkNotificationReadUsingVerifiedSubject() {
        NotificationResponse response = NotificationResponse.builder()
                .id(100L)
                .userId(42L)
                .status("READ")
                .build();
        when(notificationService.markAsRead(100L, 42L)).thenReturn(response);

        userClient("42")
                .put()
                .uri("/notification/100/read")
                .header("X-User-ID", "999")
                .exchange()
                .expectStatus().isOk();

        verify(notificationService).markAsRead(100L, 42L);
    }

    @Test
    void rejectsNonnumericAuthenticatedSubject() {
        userClient("user-42")
                .get()
                .uri("/notification/user/42")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    @Test
    void deniesUserTokenFromCreatingArbitraryNotification() {
        userClient("42")
                .post()
                .uri("/notification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "userId": 42,
                          "type": "IN_APP",
                          "body": "caller-authored notification"
                        }
                        """)
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/notification/email/send",
            "/notification/email/verification",
            "/notification/email/processing-reminder",
            "/notification/email/system-admin-approval",
            "/notification/email/notification"
    })
    void deniesArbitraryEmailEndpointsEvenToSystemAdmin(String path) {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt
                        .subject("1")
                        .claim("roles", List.of("SYSTEM_ADMIN"))))
                .post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(emailService);
    }

    @Test
    void deniesUnlistedRoutesForAuthenticatedUsers() {
        userClient("42")
                .get()
                .uri("/notification/unlisted")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(notificationService);
    }

    private WebTestClient userClient(String subject) {
        return webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt
                .subject(subject)
                .claim("roles", List.of("PATIENT"))));
    }
}
