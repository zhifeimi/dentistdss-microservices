package press.mizhifei.dentist.clinicalrecords.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import press.mizhifei.dentist.clinicalrecords.dto.TreatmentPlanRequest;
import press.mizhifei.dentist.clinicalrecords.dto.TreatmentPlanResponse;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;
import press.mizhifei.dentist.clinicalrecords.service.TreatmentPlanService;

import java.util.List;

@RestController
@RequestMapping("/clinical-records/treatment-plan")
@RequiredArgsConstructor
public class TreatmentPlanController {

    private final TreatmentPlanService treatmentPlanService;

    @PostMapping
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<TreatmentPlanResponse>> createTreatmentPlan(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TreatmentPlanRequest request) {
        TreatmentPlanResponse response = treatmentPlanService.createTreatmentPlan(
                ClinicalRecordsActor.from(jwt),
                request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasRole('PATIENT')")
    public ResponseEntity<ApiResponse<TreatmentPlanResponse>> acceptTreatmentPlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Integer id) {
        TreatmentPlanResponse response = treatmentPlanService.acceptTreatmentPlan(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<TreatmentPlanResponse>> startTreatmentPlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Integer id) {
        TreatmentPlanResponse response = treatmentPlanService.startTreatmentPlan(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<TreatmentPlanResponse>> completeTreatmentPlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Integer id) {
        TreatmentPlanResponse response = treatmentPlanService.completeTreatmentPlan(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{planId}/item/{itemId}/status")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<TreatmentPlanResponse>> updateItemStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Integer planId,
            @PathVariable Integer itemId,
            @RequestParam String status) {
        TreatmentPlanResponse response = treatmentPlanService.updateTreatmentPlanItemStatus(
                ClinicalRecordsActor.from(jwt),
                planId,
                itemId,
                status);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<TreatmentPlanResponse>> getTreatmentPlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Integer id) {
        TreatmentPlanResponse response = treatmentPlanService.getTreatmentPlan(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<TreatmentPlanResponse>>> getPatientTreatmentPlans(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId) {
        List<TreatmentPlanResponse> plans = treatmentPlanService.getPatientTreatmentPlans(
                ClinicalRecordsActor.from(jwt),
                patientId);
        return ResponseEntity.ok(ApiResponse.success(plans));
    }

    @GetMapping("/dentist/{dentistId}")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<TreatmentPlanResponse>>> getDentistTreatmentPlans(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long dentistId) {
        List<TreatmentPlanResponse> plans = treatmentPlanService.getDentistTreatmentPlans(
                ClinicalRecordsActor.from(jwt),
                dentistId);
        return ResponseEntity.ok(ApiResponse.success(plans));
    }

    @GetMapping("/{parentPlanId}/versions")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<TreatmentPlanResponse>>> getPlanVersions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Integer parentPlanId) {
        List<TreatmentPlanResponse> plans = treatmentPlanService.getPlanVersions(
                ClinicalRecordsActor.from(jwt),
                parentPlanId);
        return ResponseEntity.ok(ApiResponse.success(plans));
    }
}
