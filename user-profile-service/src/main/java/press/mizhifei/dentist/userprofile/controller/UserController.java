package press.mizhifei.dentist.userprofile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import press.mizhifei.dentist.security.AuthenticatedUser;
import press.mizhifei.dentist.userprofile.dto.ApiResponse;
import press.mizhifei.dentist.userprofile.dto.UserResponse;
import press.mizhifei.dentist.userprofile.dto.UserUpdateRequest;
import press.mizhifei.dentist.userprofile.model.User;
import press.mizhifei.dentist.userprofile.service.UserService;
import press.mizhifei.dentist.userprofile.service.UserService.UserDetailsResponse;

import java.util.List;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/list/all")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<UserResponse>>> listAllUsers() {
        List<UserResponse> users = userService.listAllUsers();
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @GetMapping("/{id}/email")
    public String getUserEmail(@PathVariable Long id, Authentication authentication) {
        if (hasContactReadAuthority(authentication)) {
            return userService.getUserEmail(id);
        }
        requireSelfOrSystemAdmin(id, authentication);
        return userService.getUserEmail(id);
    }

    @GetMapping("/{id}/name")
    public String getUserFullName(@PathVariable Long id) {
        return userService.getUserFullName(id);
    }

    @GetMapping("/{id}/details")
    public ResponseEntity<ApiResponse<UserDetailsResponse>> getUserDetails(
            @PathVariable Long id,
            Authentication authentication) {
        requireSelfOrSystemAdmin(id, authentication);
        return ResponseEntity.ok(userService.getUserDetails(id));
    }

    @GetMapping("/email/{email}/details")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'CLINIC_ADMIN', 'RECEPTIONIST')")
    public UserDetailsResponse getUserDetailsByEmail(@PathVariable String email) {
        User user = userService.getUserByEmail(email);
        return new UserDetailsResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getAddress(),
                user.getRoles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                user.getClinicId(),
                user.getClinicName(),
                user.isEnabled()
        );
    }

    @PutMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserResponse>> updateUserProfile(
            @PathVariable Long userId,
            @Valid @RequestBody UserUpdateRequest updateRequest,
            Authentication authentication) {
        requireSelfOrSystemAdmin(userId, authentication);
        ApiResponse<UserResponse> response = userService.updateUserProfile(userId, updateRequest);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/clinic/{clinicId}/dentists")
    public List<UserResponse> getClinicDentists(@PathVariable Long clinicId) {
        return userService.getClinicDentists(clinicId);
    }

    /**
     * Verified service callers holding the {@code user:contact:read} scope
     * (granted to notification-service for recipient resolution) may read
     * contact details for any user. The scope is bound to the caller's key by
     * the service-credential decoder, so the authority is server-verified.
     */
    private boolean hasContactReadAuthority(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream().anyMatch(authority ->
                        "SERVICE_USER_CONTACT_READ".equals(authority.getAuthority()));
    }

    /**
     * Users may read or update only their own record; the system admin is
     * unrestricted.
     */
    private void requireSelfOrSystemAdmin(Long requestedUserId, Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtToken)) {
            throw new AccessDeniedException("Authenticated principal is not a JWT");
        }
        AuthenticatedUser user = AuthenticatedUser.from(jwtToken.getToken());
        if (user.hasRole("SYSTEM_ADMIN")) {
            return;
        }
        long authenticatedUserId;
        try {
            authenticatedUserId = user.requiredNumericUserId();
        } catch (IllegalStateException ex) {
            throw new AccessDeniedException("Authenticated subject is invalid", ex);
        }
        if (!requestedUserId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("User records are limited to the current user");
        }
    }
}
