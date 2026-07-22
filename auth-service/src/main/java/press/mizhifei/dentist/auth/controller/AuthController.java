package press.mizhifei.dentist.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.AuthResponse;
import press.mizhifei.dentist.auth.dto.ChangePasswordRequest;
import press.mizhifei.dentist.auth.dto.LoginRequest;
import press.mizhifei.dentist.auth.dto.SessionTokens;
import press.mizhifei.dentist.auth.dto.SignUpClinicAdminRequest;
import press.mizhifei.dentist.auth.dto.SignUpRequest;
import press.mizhifei.dentist.auth.dto.SignUpStaffRequest;
import press.mizhifei.dentist.auth.dto.UserResponse;
import press.mizhifei.dentist.auth.dto.VerifyCodeRequest;
import press.mizhifei.dentist.auth.service.AuthCookieService;
import press.mizhifei.dentist.auth.service.AuthService;
import press.mizhifei.dentist.auth.service.AuthSessionService;
import press.mizhifei.dentist.auth.service.SecurityStateService;
import press.mizhifei.dentist.auth.security.UserPrincipal;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthSessionService authSessionService;
    private final AuthCookieService authCookieService;
    private final SecurityStateService securityStateService;

    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
        return ResponseEntity.noContent()
                .header(AuthCookieService.CSRF_HEADER, csrfToken.getToken())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> authenticateUser(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request) {
        String identity = loginRequest.getEmail() + ":" + request.getRemoteAddr();
        if (!securityStateService.isAllowed("login", identity, 10, Duration.ofMinutes(15))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Unable to sign in"));
        }

        try {
            SessionTokens tokens = authSessionService.authenticate(loginRequest);
            HttpHeaders headers = new HttpHeaders();
            authCookieService.addSessionCookies(headers, tokens);
            return ResponseEntity.ok().headers(headers).body(ApiResponse.success(tokens.response()));
        } catch (BadCredentialsException | AccountStatusException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Your email or password is incorrect"));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            HttpServletRequest request,
            @CookieValue(name = AuthCookieService.CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = AuthCookieService.CSRF_HEADER, required = false) String csrfHeader) {
        String refreshToken = authCookieService.readRefreshToken(request);
        if (!StringUtils.hasText(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Authentication required"));
        }
        authCookieService.validateCsrf(csrfHeader, csrfCookie);
        try {
            SessionTokens tokens = authSessionService.refresh(refreshToken);
            HttpHeaders headers = new HttpHeaders();
            authCookieService.addSessionCookies(headers, tokens);
            return ResponseEntity.ok().headers(headers).body(ApiResponse.success(tokens.response()));
        } catch (BadCredentialsException ex) {
            HttpHeaders headers = new HttpHeaders();
            authCookieService.clearSessionCookies(headers);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .headers(headers)
                    .body(ApiResponse.error("Authentication required"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logoutUser(
            HttpServletRequest request,
            @CookieValue(name = AuthCookieService.CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = AuthCookieService.CSRF_HEADER, required = false) String csrfHeader,
            Authentication authentication) {
        String refreshToken = authCookieService.readRefreshToken(request);
        if (StringUtils.hasText(refreshToken)) {
            authCookieService.validateCsrf(csrfHeader, csrfCookie);
        }

        HttpHeaders headers = new HttpHeaders();
        try {
            AuthSessionService.RevokedFamily revokedRefreshFamily = null;
            if (StringUtils.hasText(refreshToken)) {
                revokedRefreshFamily = authSessionService.revoke(refreshToken).orElse(null);
            }
            if (authentication != null
                    && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof UserPrincipal principal
                    && principal.getId() != null
                    && StringUtils.hasText(principal.getSessionFamilyId())
                    && !sameFamily(revokedRefreshFamily, principal)) {
                authSessionService.revokeFamily(principal.getId(), principal.getSessionFamilyId());
            }
        } catch (DataAccessException | SecurityStateService.SecurityStateUnavailableException ex) {
            log.error("Logout revocation state is unavailable", ex);
            authCookieService.clearSessionCookies(headers);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .headers(headers)
                    .body(ApiResponse.error("Logout could not be completed"));
        }

        authCookieService.clearSessionCookies(headers);
        return ResponseEntity.ok().headers(headers)
                .body(ApiResponse.successMessage("User logged out successfully"));
    }

    @ExceptionHandler({DataAccessException.class, SecurityStateService.SecurityStateUnavailableException.class})
    public ResponseEntity<ApiResponse<String>> authenticationStateUnavailable(RuntimeException ex) {
        log.error("Authentication infrastructure is unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Authentication service temporarily unavailable"));
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<String>> registerUser(@Valid @RequestBody SignUpRequest signUpRequest) {
        return ResponseEntity.ok(authService.registerUser(signUpRequest));
    }

    @PostMapping("/signup/clinic/staff")
    public ResponseEntity<ApiResponse<String>> registerStaff(@Valid @RequestBody SignUpStaffRequest signUpStaffRequest) {
        return ResponseEntity.ok(authService.registerStaff(signUpStaffRequest));
    }

    @PostMapping("/signup/clinic/admin")
    public ResponseEntity<ApiResponse<String>> registerClinicAdmin(
            @Valid @RequestBody SignUpClinicAdminRequest signUpClinicAdminRequest) {
        return ResponseEntity.ok(authService.registerClinicAdmin(signUpClinicAdminRequest));
    }

    @PostMapping("/signup/verify/code/resend")
    public ResponseEntity<ApiResponse<String>> resendVerificationCode(@RequestParam("email") String email) {
        if (!securityStateService.isAllowed("verification-resend", email, 3, Duration.ofHours(1))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.successMessage("If verification is available, use your current code or check your email for a code"));
        }
        authService.resendVerificationCode(email);
        return ResponseEntity.ok(ApiResponse.successMessage("If verification is available, use your current code or check your email for a code"));
    }

    @PostMapping("/signup/verify/code")
    public ResponseEntity<ApiResponse<String>> verifyEmailWithCode(@Valid @RequestBody VerifyCodeRequest verifyRequest) {
        if (!securityStateService.isAllowed("verification-attempt", verifyRequest.getEmail(), 6, Duration.ofMinutes(15))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Unable to verify code"));
        }
        return ResponseEntity.ok(authService.verifyEmailByCode(
                verifyRequest.getEmail(),
                verifyRequest.getCode(),
                verifyRequest.getNewPassword()));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser() {
        return ResponseEntity.ok(authService.getCurrentUser());
    }

    @PostMapping("/password/change")
    public ResponseEntity<ApiResponse<String>> changePassword(
            @Valid @RequestBody ChangePasswordRequest changePasswordRequest) {
        return ResponseEntity.ok(authService.changePassword(changePasswordRequest));
    }

    private boolean sameFamily(
            AuthSessionService.RevokedFamily revokedFamily,
            UserPrincipal principal) {
        return revokedFamily != null
                && revokedFamily.userId() == principal.getId()
                && revokedFamily.familyId().equals(principal.getSessionFamilyId());
    }
}
