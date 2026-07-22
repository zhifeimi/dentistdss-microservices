package press.mizhifei.dentist.auth.controller;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.AuthResponse;
import press.mizhifei.dentist.auth.dto.IdTokenRequest;
import press.mizhifei.dentist.auth.service.AuthCookieService;
import press.mizhifei.dentist.auth.service.AuthSessionService;
import press.mizhifei.dentist.auth.service.OAuthUserService;
import press.mizhifei.dentist.auth.service.SecurityStateService;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuth2ControllerTest {

    private GoogleIdTokenVerifier verifier;
    private OAuthUserService oAuthUserService;
    private SecurityStateService securityStateService;
    private OAuth2Controller controller;
    private GoogleIdToken.Payload payload;

    @BeforeEach
    void setUp() throws Exception {
        verifier = mock(GoogleIdTokenVerifier.class);
        oAuthUserService = mock(OAuthUserService.class);
        securityStateService = mock(SecurityStateService.class);
        controller = new OAuth2Controller(
                verifier,
                oAuthUserService,
                mock(AuthSessionService.class),
                mock(AuthCookieService.class),
                securityStateService);

        GoogleIdToken idToken = mock(GoogleIdToken.class);
        payload = mock(GoogleIdToken.Payload.class);
        when(verifier.verify("id-token")).thenReturn(idToken);
        when(idToken.getPayload()).thenReturn(payload);
        when(payload.get("nonce")).thenReturn("nonce");
        when(payload.getEmailVerified()).thenReturn(true);
        when(payload.getSubject()).thenReturn("google-subject");
        when(payload.getEmail()).thenReturn("patient@example.com");
        when(securityStateService.isAllowed(
                eq("google-login"),
                anyString(),
                eq(20),
                eq(Duration.ofMinutes(15))))
                .thenReturn(true);
        when(securityStateService.consumeGoogleNonce("nonce")).thenReturn(true);
    }

    @Test
    void expectedIdentityRejectionUsesGenericUnauthorizedResponse() throws Exception {
        when(oAuthUserService.processVerifiedGoogleIdentity(
                "google-subject",
                "patient@example.com",
                null,
                null))
                .thenThrow(new BadCredentialsException("account unavailable"));

        ResponseEntity<ApiResponse<AuthResponse>> response = controller
                .authenticateWithGoogleIdToken(
                        new IdTokenRequest("id-token", "nonce"),
                        new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Authentication failed", response.getBody().getMessage());
    }

    @Test
    void persistenceFailureIsNotMisreportedAsInvalidIdentity() {
        when(oAuthUserService.processVerifiedGoogleIdentity(
                "google-subject",
                "patient@example.com",
                null,
                null))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThrows(DataAccessResourceFailureException.class, () ->
                controller.authenticateWithGoogleIdToken(
                        new IdTokenRequest("id-token", "nonce"),
                        new MockHttpServletRequest()));
    }
}
