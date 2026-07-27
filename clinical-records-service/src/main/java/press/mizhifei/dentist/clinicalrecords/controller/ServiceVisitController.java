package press.mizhifei.dentist.clinicalrecords.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import press.mizhifei.dentist.clinicalrecords.dto.ApiResponse;
import press.mizhifei.dentist.clinicalrecords.dto.ServiceVisitRequest;
import press.mizhifei.dentist.clinicalrecords.dto.ServiceVisitResponse;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;
import press.mizhifei.dentist.clinicalrecords.service.ServiceVisitService;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/clinical-records/visit")
@RequiredArgsConstructor
public class ServiceVisitController {

    private final ServiceVisitService serviceVisitService;

    @PostMapping
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ServiceVisitResponse>> createServiceVisit(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ServiceVisitRequest request) {
        ServiceVisitResponse response = serviceVisitService.createServiceVisit(
                ClinicalRecordsActor.from(jwt),
                request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ServiceVisitResponse>> checkInVisit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        ServiceVisitResponse response = serviceVisitService.checkInVisit(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/check-out")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ServiceVisitResponse>> checkOutVisit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        ServiceVisitResponse response = serviceVisitService.checkOutVisit(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ServiceVisitResponse>> updateVisitNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestBody String notes) {
        ServiceVisitResponse response = serviceVisitService.updateVisitNotes(
                ClinicalRecordsActor.from(jwt),
                id,
                notes);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ServiceVisitResponse>> cancelVisit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        ServiceVisitResponse response = serviceVisitService.cancelVisit(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ServiceVisitResponse>> getServiceVisit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        ServiceVisitResponse response = serviceVisitService.getServiceVisit(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ServiceVisitResponse>>> getPatientVisits(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId) {
        List<ServiceVisitResponse> visits = serviceVisitService.getPatientVisits(
                ClinicalRecordsActor.from(jwt),
                patientId);
        return ResponseEntity.ok(ApiResponse.success(visits));
    }

    @GetMapping("/patient/{patientId}/date-range")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ServiceVisitResponse>>> getPatientVisitsByDateRange(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        List<ServiceVisitResponse> visits = serviceVisitService.getPatientVisitsByDateRange(
                ClinicalRecordsActor.from(jwt),
                patientId,
                startDate,
                endDate);
        return ResponseEntity.ok(ApiResponse.success(visits));
    }

    @GetMapping("/dentist/{dentistId}")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ServiceVisitResponse>>> getDentistVisits(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long dentistId) {
        List<ServiceVisitResponse> visits = serviceVisitService.getDentistVisits(
                ClinicalRecordsActor.from(jwt),
                dentistId);
        return ResponseEntity.ok(ApiResponse.success(visits));
    }

    @GetMapping("/clinic/{clinicId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ServiceVisitResponse>>> getClinicVisits(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long clinicId) {
        List<ServiceVisitResponse> visits = serviceVisitService.getClinicVisits(
                ClinicalRecordsActor.from(jwt),
                clinicId);
        return ResponseEntity.ok(ApiResponse.success(visits));
    }

    @GetMapping("/clinic/{clinicId}/status/{status}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ServiceVisitResponse>>> getClinicVisitsByStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long clinicId,
            @PathVariable String status) {
        List<ServiceVisitResponse> visits = serviceVisitService.getClinicVisitsByStatus(
                ClinicalRecordsActor.from(jwt),
                clinicId,
                status);
        return ResponseEntity.ok(ApiResponse.success(visits));
    }

    @GetMapping("/appointment/{appointmentId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ServiceVisitResponse>> getVisitByAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long appointmentId) {
        ServiceVisitResponse response = serviceVisitService.getVisitByAppointment(
                ClinicalRecordsActor.from(jwt),
                appointmentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
