package press.mizhifei.dentist.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import press.mizhifei.dentist.auth.audit.AuditEventPublisher;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.AuthResponse;
import press.mizhifei.dentist.auth.dto.LoginRequest;
import press.mizhifei.dentist.auth.dto.SessionTokens;
import press.mizhifei.dentist.auth.dto.VerifyCodeRequest;
import press.mizhifei.dentist.auth.service.AuthCookieService;
import press.mizhifei.dentist.auth.service.AuthService;
import press.mizhifei.dentist.auth.service.AuthSessionService;
import press.mizhifei.dentist.auth.service.SecurityStateService;
import press.mizhifei.dentist.auth.security.UserPrincipal;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private AuthService authService;
    private AuthSessionService authSessionService;
    private AuthCookieService authCookieService;
    private SecurityStateService securityStateService;
    private AuditEventPublisher auditEventPublisher;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        authSessionService = mock(AuthSessionService.class);
        authCookieService = mock(AuthCookieService.class);
        securityStateService = mock(SecurityStateService.class);
        auditEventPublisher = mock(AuditEventPublisher.class);
        controller = new AuthController(
                authService,
                authSessionService,
                authCookieService,
                securityStateService,
                auditEventPublisher);
        when(securityStateService.isAllowed(
                anyString(),
                anyString(),
                anyInt(),
                any()))
                .thenReturn(true);
    }

    @Test
    void refreshRejectsRequestWithoutConfiguredRefreshCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn(null);

        ResponseEntity<ApiResponse<AuthResponse>> response = controller.refresh(
                request,
                "csrf-cookie",
                "csrf-header");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(authCookieService, never()).validateCsrf(any(), any());
        verify(authSessionService, never()).refresh(any());
    }

    @Test
    void refreshValidatesCsrfBeforeRotatingRefreshToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        SessionTokens tokens = new SessionTokens(
                AuthResponse.builder().accessToken("access-token").build(),
                "rotated-refresh-token",
                "rotated-csrf-token");
        when(authCookieService.readRefreshToken(request)).thenReturn("refresh-token");
        when(authSessionService.refresh("refresh-token")).thenReturn(tokens);

        ResponseEntity<ApiResponse<AuthResponse>> response = controller.refresh(
                request,
                "csrf-cookie",
                "csrf-header");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(authCookieService).validateCsrf("csrf-header", "csrf-cookie");
        verify(authSessionService).refresh("refresh-token");
        verify(authCookieService).addSessionCookies(any(), any());
    }

    @Test
    void refreshMapsRejectedRotationToGenericUnauthorizedAndClearsCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn("reused-token");
        when(authSessionService.refresh("reused-token"))
                .thenThrow(new BadCredentialsException("reuse detected"));

        ResponseEntity<ApiResponse<AuthResponse>> response = controller.refresh(
                request,
                "csrf-cookie",
                "csrf-header");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Authentication required", response.getBody().getMessage());
        verify(authCookieService).validateCsrf("csrf-header", "csrf-cookie");
        verify(authCookieService).clearSessionCookies(any());
    }

    @Test
    void loginMapsLockedAccountToSameGenericCredentialResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        LoginRequest loginRequest = new LoginRequest("patient@example.com", "password");
        when(authSessionService.authenticate(loginRequest))
                .thenThrow(new LockedException("locked"));

        ResponseEntity<ApiResponse<AuthResponse>> response = controller
                .authenticateUser(loginRequest, request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Your email or password is incorrect", response.getBody().getMessage());
    }

    @Test
    void securityStateFailureMapsToSanitizedServiceUnavailable() {
        ResponseEntity<ApiResponse<String>> response = controller.authenticationStateUnavailable(
                new SecurityStateService.SecurityStateUnavailableException(
                        new IllegalStateException("redis host details")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "Authentication service temporarily unavailable",
                response.getBody().getMessage());
    }

    @Test
    void redisFailureMapsToSameSanitizedServiceUnavailable() {
        ResponseEntity<ApiResponse<String>> response = controller.authenticationStateUnavailable(
                new RedisConnectionFailureException("redis host details"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "Authentication service temporarily unavailable",
                response.getBody().getMessage());
    }

    @Test
    void logoutWithoutRefreshCookieStillClearsBrowserCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn(null);

        ResponseEntity<ApiResponse<String>> response = controller.logoutUser(
                request,
                null,
                null,
                null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(authCookieService, never()).validateCsrf(any(), any());
        verify(authSessionService, never()).revoke(any());
        verify(authCookieService).clearSessionCookies(any());
    }

    @Test
    void logoutValidatesCsrfBeforeRevokingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn("refresh-token");

        controller.logoutUser(request, "csrf-cookie", "csrf-header", null);

        verify(authCookieService).validateCsrf("csrf-header", "csrf-cookie");
        verify(authSessionService).revoke("refresh-token");
        verify(authCookieService).clearSessionCookies(any());
    }

    @Test
    void logoutDoesNotRevokeSameRefreshAndBearerFamilyTwice() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn("refresh-token");
        when(authSessionService.revoke("refresh-token"))
                .thenReturn(Optional.of(new AuthSessionService.RevokedFamily(42L, "family-1")));
        UserPrincipal principal = UserPrincipal.builder()
                .id(42L)
                .sessionFamilyId("family-1")
                .enabled(true)
                .build();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                java.util.List.of());

        controller.logoutUser(request, "csrf-cookie", "csrf-header", authentication);

        verify(authSessionService).revoke("refresh-token");
        verify(authSessionService, never()).revokeFamily(42L, "family-1");
        verify(authCookieService).clearSessionCookies(any());
    }

    @Test
    void logoutEmitsAuditEventForTheRevokedFamily() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn("refresh-token");
        when(authSessionService.revoke("refresh-token"))
                .thenReturn(Optional.of(new AuthSessionService.RevokedFamily(42L, "family-1")));

        controller.logoutUser(request, "csrf-cookie", "csrf-header", null);

        verify(auditEventPublisher).publish(
                eq("LOGOUT"), eq("user:42"), eq(42L), eq(null),
                eq(java.util.Map.of("familyId", "family-1")));
    }

    @Test
    void logoutEmitsAuditEventFromTheBearerIdentityWhenNoRefreshCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn(null);
        UserPrincipal principal = UserPrincipal.builder()
                .id(43L)
                .sessionFamilyId("family-9")
                .enabled(true)
                .build();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                java.util.List.of());

        controller.logoutUser(request, null, null, authentication);

        verify(auditEventPublisher).publish(
                eq("LOGOUT"), eq("user:43"), eq(43L), eq(null),
                eq(java.util.Map.of("familyId", "family-9")));
    }

    @Test
    void anonymousLogoutEmitsNoAuditEvent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn(null);

        controller.logoutUser(request, null, null, null);

        verify(auditEventPublisher, never())
                .publish(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void failedLogoutEmitsNoAuditEvent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn("refresh-token");
        doThrow(new SecurityStateService.SecurityStateUnavailableException(
                new RedisConnectionFailureException("unavailable")))
                .when(authSessionService).revoke("refresh-token");

        controller.logoutUser(request, "csrf-cookie", "csrf-header", null);

        verify(auditEventPublisher, never())
                .publish(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void logoutClearsCookiesAndReturnsUnavailableWhenRevocationStateFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn("refresh-token");
        doThrow(new SecurityStateService.SecurityStateUnavailableException(
                new RedisConnectionFailureException("unavailable")))
                .when(authSessionService).revoke("refresh-token");

        ResponseEntity<ApiResponse<String>> response = controller.logoutUser(
                request,
                "csrf-cookie",
                "csrf-header",
                null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verify(authCookieService).validateCsrf("csrf-header", "csrf-cookie");
        verify(authCookieService).clearSessionCookies(any());
    }

    @Test
    void bearerOnlyLogoutRevokesVerifiedAccessTokenFamilyWithoutCsrf() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authCookieService.readRefreshToken(request)).thenReturn(null);
        UserPrincipal principal = UserPrincipal.builder()
                .id(42L)
                .sessionFamilyId("family-1")
                .enabled(true)
                .build();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                java.util.List.of());

        controller.logoutUser(request, null, null, authentication);

        verify(authCookieService, never()).validateCsrf(any(), any());
        verify(authSessionService).revokeFamily(42L, "family-1");
        verify(authCookieService).clearSessionCookies(any());
    }

    @Test
    void resendUsesTruthfulEnumerationSafeMessageWhenNoNewCodeIsSent() {
        when(authService.resendVerificationCode("patient@example.com"))
                .thenReturn(ApiResponse.successMessage("Verification code already sent"));

        ResponseEntity<ApiResponse<String>> response = controller
                .resendVerificationCode("patient@example.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "If verification is available, use your current code or check your email for a code",
                response.getBody().getMessage());
        verify(authService).resendVerificationCode("patient@example.com");
    }

    @Test
    void emailVerificationForwardsMailboxOwnersReplacementPassword() {
        VerifyCodeRequest request = new VerifyCodeRequest(
                "patient@example.com",
                "123456",
                "FinalStrong1!");
        when(authService.verifyEmailByCode(
                "patient@example.com",
                "123456",
                "FinalStrong1!"))
                .thenReturn(ApiResponse.successMessage("verified"));

        ResponseEntity<ApiResponse<String>> response = controller.verifyEmailWithCode(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(authService).verifyEmailByCode(
                "patient@example.com",
                "123456",
                "FinalStrong1!");
    }
}
