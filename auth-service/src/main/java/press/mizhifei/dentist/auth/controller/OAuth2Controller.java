package press.mizhifei.dentist.auth.controller;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.AuthResponse;
import press.mizhifei.dentist.auth.dto.IdTokenRequest;
import press.mizhifei.dentist.auth.dto.SessionTokens;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.service.AuthCookieService;
import press.mizhifei.dentist.auth.service.AuthSessionService;
import press.mizhifei.dentist.auth.service.OAuthUserService;
import press.mizhifei.dentist.auth.service.SecurityStateService;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
public class OAuth2Controller {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final OAuthUserService oAuthUserService;
    private final AuthSessionService authSessionService;
    private final AuthCookieService authCookieService;
    private final SecurityStateService securityStateService;

    @GetMapping("/nonce")
    public ResponseEntity<ApiResponse<Map<String, String>>> issueNonce(HttpServletRequest request) {
        if (!securityStateService.isAllowed("google-nonce", request.getRemoteAddr(), 20, Duration.ofMinutes(10))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Unable to start Google login"));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("nonce", securityStateService.issueGoogleNonce())));
    }

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<AuthResponse>> authenticateWithGoogleIdToken(
            @Valid @RequestBody IdTokenRequest request,
            HttpServletRequest servletRequest) throws GeneralSecurityException, IOException {
        if (!securityStateService.isAllowed("google-login", servletRequest.getRemoteAddr(), 20, Duration.ofMinutes(15))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Authentication failed"));
        }

        try {
            GoogleIdToken idToken = googleIdTokenVerifier.verify(request.getIdToken());
            if (idToken == null || idToken.getPayload() == null) {
                return unauthorized();
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            Object nonceClaim = payload.get("nonce");
            if (!(nonceClaim instanceof String tokenNonce)
                    || !request.getNonce().equals(tokenNonce)
                    || !securityStateService.consumeGoogleNonce(request.getNonce())
                    || !Boolean.TRUE.equals(payload.getEmailVerified())
                    || payload.getSubject() == null
                    || payload.getEmail() == null) {
                return unauthorized();
            }

            User user = oAuthUserService.processVerifiedGoogleIdentity(
                    payload.getSubject(),
                    payload.getEmail(),
                    optionalStringClaim(payload, "given_name"),
                    optionalStringClaim(payload, "family_name"));
            SessionTokens tokens = authSessionService.issueForUser(user);
            HttpHeaders headers = new HttpHeaders();
            authCookieService.addSessionCookies(headers, tokens);
            return ResponseEntity.ok().headers(headers).body(ApiResponse.success(tokens.response()));
        } catch (BadCredentialsException | IllegalArgumentException ex) {
            log.warn("Google authentication rejected");
            return unauthorized();
        }
    }

    private String optionalStringClaim(GoogleIdToken.Payload payload, String claimName) {
        Object value = payload.get(claimName);
        return value instanceof String stringValue ? stringValue : null;
    }

    private ResponseEntity<ApiResponse<AuthResponse>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication failed"));
    }
}
