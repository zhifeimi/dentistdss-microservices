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
import press.mizhifei.dentist.clinicalrecords.dto.ClinicalNoteRequest;
import press.mizhifei.dentist.clinicalrecords.dto.ClinicalNoteResponse;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;
import press.mizhifei.dentist.clinicalrecords.service.ClinicalNoteService;

import java.util.List;

@RestController
@RequestMapping("/clinical-records/note")
@RequiredArgsConstructor
public class ClinicalNoteController {

    private final ClinicalNoteService clinicalNoteService;

    @PostMapping
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> createClinicalNote(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ClinicalNoteRequest request) {
        ClinicalNoteResponse response = clinicalNoteService.createClinicalNote(
                ClinicalRecordsActor.from(jwt),
                request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> updateClinicalNote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody ClinicalNoteRequest request) {
        ClinicalNoteResponse response = clinicalNoteService.updateClinicalNote(
                ClinicalRecordsActor.from(jwt),
                id,
                request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/sign")
    @PreAuthorize("hasRole('DENTIST')")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> signClinicalNote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        ClinicalNoteResponse response = clinicalNoteService.signClinicalNote(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> getClinicalNote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        ClinicalNoteResponse response = clinicalNoteService.getClinicalNote(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/clinic/{clinicId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getClinicClinicalNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long clinicId) {
        List<ClinicalNoteResponse> notes = clinicalNoteService.getClinicClinicalNotes(
                ClinicalRecordsActor.from(jwt),
                clinicId);
        return ResponseEntity.ok(ApiResponse.success(notes));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getPatientClinicalNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId,
            @RequestParam(defaultValue = "false") boolean includeDrafts) {
        List<ClinicalNoteResponse> notes = clinicalNoteService.getPatientClinicalNotes(
                ClinicalRecordsActor.from(jwt),
                patientId,
                includeDrafts);
        return ResponseEntity.ok(ApiResponse.success(notes));
    }

    @GetMapping("/patient/{patientId}/category/{category}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getPatientNotesByCategory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId,
            @PathVariable String category) {
        List<ClinicalNoteResponse> notes = clinicalNoteService.getPatientNotesByCategory(
                ClinicalRecordsActor.from(jwt),
                patientId,
                category);
        return ResponseEntity.ok(ApiResponse.success(notes));
    }

    @GetMapping("/patient/{patientId}/search")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> searchPatientNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId,
            @RequestParam String searchTerm) {
        List<ClinicalNoteResponse> notes = clinicalNoteService.searchPatientNotes(
                ClinicalRecordsActor.from(jwt),
                patientId,
                searchTerm);
        return ResponseEntity.ok(ApiResponse.success(notes));
    }

    @GetMapping("/dentist/{dentistId}")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getDentistClinicalNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long dentistId) {
        List<ClinicalNoteResponse> notes = clinicalNoteService.getDentistClinicalNotes(
                ClinicalRecordsActor.from(jwt),
                dentistId);
        return ResponseEntity.ok(ApiResponse.success(notes));
    }

    @GetMapping("/dentist/{dentistId}/drafts")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getDentistDraftNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long dentistId) {
        List<ClinicalNoteResponse> notes = clinicalNoteService.getDentistDraftNotes(
                ClinicalRecordsActor.from(jwt),
                dentistId);
        return ResponseEntity.ok(ApiResponse.success(notes));
    }

    @GetMapping("/appointment/{appointmentId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> getClinicalNoteByAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long appointmentId) {
        ClinicalNoteResponse response = clinicalNoteService.getClinicalNoteByAppointment(
                ClinicalRecordsActor.from(jwt),
                appointmentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getVisitClinicalNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long visitId) {
        List<ClinicalNoteResponse> notes = clinicalNoteService.getVisitClinicalNotes(
                ClinicalRecordsActor.from(jwt),
                visitId);
        return ResponseEntity.ok(ApiResponse.success(notes));
    }

    @GetMapping("/{parentNoteId}/versions")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getNoteVersions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long parentNoteId) {
        List<ClinicalNoteResponse> notes = clinicalNoteService.getNoteVersions(
                ClinicalRecordsActor.from(jwt),
                parentNoteId);
        return ResponseEntity.ok(ApiResponse.success(notes));
    }
}
