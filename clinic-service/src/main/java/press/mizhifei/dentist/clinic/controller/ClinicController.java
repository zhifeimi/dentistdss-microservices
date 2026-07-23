package press.mizhifei.dentist.clinic.controller;

import feign.FeignException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import press.mizhifei.dentist.clinic.dto.ApiResponse;
import press.mizhifei.dentist.clinic.dto.ClinicResponse;
import press.mizhifei.dentist.clinic.dto.ClinicSearchRequest;
import press.mizhifei.dentist.clinic.dto.ClinicCreateRequest;
import press.mizhifei.dentist.clinic.dto.ClinicUpdateRequest;
import press.mizhifei.dentist.clinic.dto.PatientWithAppointmentResponse;
import press.mizhifei.dentist.clinic.dto.UserResponse;
import press.mizhifei.dentist.clinic.service.ClinicService;
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
@Slf4j
@RestController
@RequestMapping("/clinic")
@RequiredArgsConstructor
public class ClinicController {

    private static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String ROLE_CLINIC_ADMIN = "CLINIC_ADMIN";
    private static final String ROLE_RECEPTIONIST = "RECEPTIONIST";

    private final ClinicService clinicService;

    @GetMapping("/list/all")
    public ResponseEntity<ApiResponse<List<ClinicResponse>>> listAllEnabledClinics() {
        List<ClinicResponse> clinics = clinicService.listAllEnabledClinics();
        return ResponseEntity.ok(ApiResponse.success(clinics));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ClinicResponse>> getClinic(@PathVariable Long id) {
        ClinicResponse response = clinicService.getClinicById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/search")
    public ResponseEntity<ApiResponse<List<ClinicResponse>>> searchClinics(@Valid @RequestBody ClinicSearchRequest request) {
        List<ClinicResponse> clinics = clinicService.searchClinics(request);
        return ResponseEntity.ok(ApiResponse.success(clinics));
    }

    @PostMapping("")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ClinicResponse>> createClinic(@Valid @RequestBody ClinicCreateRequest request) {
        ClinicResponse response = clinicService.createClinic(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // update clinic info

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ClinicResponse>> updateClinic(
            @PathVariable Long id,
            @Valid @RequestBody ClinicUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            AuthenticatedUser user = AuthenticatedUser.from(jwt);
            boolean systemAdmin = user.hasRole(ROLE_SYSTEM_ADMIN);

            if (!systemAdmin && !user.hasRole(ROLE_CLINIC_ADMIN)) {
                return ResponseEntity.status(403)
                        .body(ApiResponse.error("CLINIC_ADMIN role required"));
            }

            log.debug("User {} requesting to update clinic {}", user.email(), id);

            // A clinic admin may only update their own clinic; the system
            // admin is unrestricted.
            if (!systemAdmin && !Objects.equals(user.clinicId(), id)) {
                return ResponseEntity.status(403)
                        .body(ApiResponse.error("Access denied. You can only update your own clinic."));
            }

            ClinicResponse response = clinicService.updateClinic(id, request);
            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("Clinic not found"));
        } catch (Exception e) {
            log.error("Error updating clinic {}: {}", id, e.getMessage());
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to update clinic"));
        }
    }

    /**
     * Get patients for a clinic sorted by upcoming appointments
     * Requires CLINIC_ADMIN or RECEPTIONIST role scoped to that clinic
     * (SYSTEM_ADMIN is unrestricted)
     */
    @GetMapping("/{clinicId}/patients")
    public ResponseEntity<ApiResponse<List<PatientWithAppointmentResponse>>> getClinicPatients(
            @PathVariable Long clinicId,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            AuthenticatedUser user = AuthenticatedUser.from(jwt);

            log.debug("User {} requesting patients for clinic {}", user.email(), clinicId);

            // Check if user has required roles
            if (!user.hasRole(ROLE_SYSTEM_ADMIN)
                    && !user.hasRole(ROLE_CLINIC_ADMIN)
                    && !user.hasRole(ROLE_RECEPTIONIST)) {
                return ResponseEntity.status(403)
                        .body(ApiResponse.error("CLINIC_ADMIN or RECEPTIONIST role required"));
            }

            // Clinic staff may only view patients from their own clinic
            if (!user.hasRole(ROLE_SYSTEM_ADMIN) && !Objects.equals(user.clinicId(), clinicId)) {
                return ResponseEntity.status(403)
                        .body(ApiResponse.error("Access denied. You can only view patients from your own clinic."));
            }

            // Get patients sorted by appointments
            List<PatientWithAppointmentResponse> patients = clinicService.getClinicPatientsSortedByAppointments(clinicId);

            log.debug("Returning {} patients for clinic {}", patients.size(), clinicId);
            return ResponseEntity.ok(ApiResponse.success(patients));

        } catch (FeignException e) {
            if (e.status() == 401) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Authentication required"));
            }
            if (e.status() == 403) {
                return ResponseEntity.status(403)
                        .body(ApiResponse.error("Access denied"));
            }
            if (e.status() == 503) {
                return ResponseEntity.status(503)
                        .body(ApiResponse.error("Appointment service is temporarily unavailable"));
            }
            log.error("Appointment service request failed for clinic {}", clinicId);
            return ResponseEntity.status(502)
                    .body(ApiResponse.error("Appointment service request failed"));
        } catch (Exception e) {
            log.error("Error getting patients for clinic {}", clinicId, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Internal server error"));
        }
    }

    /**
     * Get dentists for a clinic
     * Any authenticated user (needed by the booking flow)
     */
    @GetMapping("/{clinicId}/dentists")
    public ResponseEntity<ApiResponse<List<UserResponse>>> getClinicDentists(@PathVariable Long clinicId) {
        try {
            List<UserResponse> dentists = clinicService.getClinicDentists(clinicId);
            return ResponseEntity.ok(ApiResponse.success(dentists));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("Clinic not found"));
        } catch (Exception e) {
            log.error("Error getting dentists for clinic {}: {}", clinicId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Internal server error"));
        }
    }
}
