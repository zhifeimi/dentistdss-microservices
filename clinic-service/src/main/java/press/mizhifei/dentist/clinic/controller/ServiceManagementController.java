package press.mizhifei.dentist.clinic.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import press.mizhifei.dentist.clinic.dto.ApiResponse;
import press.mizhifei.dentist.clinic.dto.ServiceRequest;
import press.mizhifei.dentist.clinic.dto.ServiceResponse;
import press.mizhifei.dentist.clinic.service.ServiceManagementService;
import press.mizhifei.dentist.security.AuthenticatedUser;

import java.util.List;
import java.util.Objects;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@RestController
@RequestMapping("/clinic/service")
@RequiredArgsConstructor
public class ServiceManagementController {

    private final ServiceManagementService serviceManagementService;

    @PostMapping
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ServiceResponse>> createService(
            @Valid @RequestBody ServiceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<ApiResponse<ServiceResponse>> clinicDenied =
                requireClinicOwnership(request.getClinicId(), jwt);
        if (clinicDenied != null) {
            return clinicDenied;
        }
        ServiceResponse response = serviceManagementService.createService(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ServiceResponse>> updateService(
            @PathVariable Integer id,
            @Valid @RequestBody ServiceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<ApiResponse<ServiceResponse>> clinicDenied =
                requireClinicOwnership(serviceManagementService.getService(id).getClinicId(), jwt);
        if (clinicDenied != null) {
            return clinicDenied;
        }
        ServiceResponse response = serviceManagementService.updateService(id, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteService(
            @PathVariable Integer id,
            @AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<ApiResponse<Void>> clinicDenied =
                requireClinicOwnership(serviceManagementService.getService(id).getClinicId(), jwt);
        if (clinicDenied != null) {
            return clinicDenied;
        }
        serviceManagementService.deleteService(id);
        return ResponseEntity.ok(ApiResponse.successMessage("Service deleted successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ServiceResponse>> getService(@PathVariable Integer id) {
        ServiceResponse response = serviceManagementService.getService(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/clinic/{clinicId}")
    public ResponseEntity<ApiResponse<List<ServiceResponse>>> getClinicServices(
            @PathVariable Long clinicId,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        List<ServiceResponse> services = serviceManagementService.getClinicServices(clinicId, activeOnly);
        return ResponseEntity.ok(ApiResponse.success(services));
    }

    @GetMapping("/clinic/{clinicId}/category/{category}")
    public ResponseEntity<ApiResponse<List<ServiceResponse>>> getServicesByCategory(
            @PathVariable Long clinicId,
            @PathVariable String category) {
        List<ServiceResponse> services = serviceManagementService.getServicesByCategory(clinicId, category);
        return ResponseEntity.ok(ApiResponse.success(services));
    }

    @GetMapping("/clinic/{clinicId}/categories")
    public ResponseEntity<ApiResponse<List<String>>> getServiceCategories(@PathVariable Long clinicId) {
        List<String> categories = serviceManagementService.getServiceCategories(clinicId);
        return ResponseEntity.ok(ApiResponse.success(categories));
    }

    /**
     * A clinic admin may only manage services that belong to their own clinic;
     * the system admin is unrestricted.
     */
    private <T> ResponseEntity<ApiResponse<T>> requireClinicOwnership(Long serviceClinicId, Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        if (user.hasRole("SYSTEM_ADMIN")) {
            return null;
        }
        if (!Objects.equals(user.clinicId(), serviceClinicId)) {
            return ResponseEntity.status(403)
                    .body(ApiResponse.error("Access denied. You can only manage services of your own clinic."));
        }
        return null;
    }
}
