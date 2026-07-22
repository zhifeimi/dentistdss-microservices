package press.mizhifei.dentist.clinicalrecords.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import press.mizhifei.dentist.clinicalrecords.dto.ApiResponse;
import press.mizhifei.dentist.clinicalrecords.dto.DentalImageResponse;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;
import press.mizhifei.dentist.clinicalrecords.service.DentalImageService;

import java.io.InputStream;
import java.util.List;

@RestController
@RequestMapping("/clinical-records/image")
@RequiredArgsConstructor
public class DentalImageController {

    private final DentalImageService dentalImageService;

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<DentalImageResponse>> uploadDentalImage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,
            @RequestParam("patientId") Long patientId,
            @RequestParam(value = "dentistId", required = false) Long dentistId,
            @RequestParam(value = "clinicId", required = false) Long clinicId,
            @RequestParam(value = "clinicalNoteId", required = false) Long clinicalNoteId,
            @RequestParam(value = "visitId", required = false) Long visitId,
            @RequestParam("imageType") String imageType,
            @RequestParam(value = "toothNumber", required = false) String toothNumber,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "tags", required = false) String tags) {
        DentalImageResponse response = dentalImageService.uploadDentalImage(
                ClinicalRecordsActor.from(jwt),
                file,
                patientId,
                dentistId,
                clinicId,
                clinicalNoteId,
                visitId,
                imageType,
                toothNumber,
                description,
                tags);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<InputStreamResource> downloadDentalImage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        InputStream imageStream = dentalImageService.downloadDentalImage(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dental_image_" + id + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(imageStream));
    }

    @GetMapping("/{id}/thumbnail")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<InputStreamResource> downloadThumbnail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        InputStream thumbnailStream = dentalImageService.downloadThumbnail(
                ClinicalRecordsActor.from(jwt),
                id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new InputStreamResource(thumbnailStream));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteDentalImage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        dentalImageService.deleteDentalImage(ClinicalRecordsActor.from(jwt), id);
        return ResponseEntity.ok(ApiResponse.success(null, "Image deleted successfully"));
    }

    @PutMapping("/{id}/metadata")
    @PreAuthorize("hasAnyRole('DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<DentalImageResponse>> updateImageMetadata(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "toothNumber", required = false) String toothNumber,
            @RequestParam(value = "isPrimary", required = false) Boolean isPrimary) {
        DentalImageResponse response = dentalImageService.updateImageMetadata(
                ClinicalRecordsActor.from(jwt),
                id,
                description,
                tags,
                toothNumber,
                isPrimary);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<DentalImageResponse>>> getPatientImages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId) {
        List<DentalImageResponse> images = dentalImageService.getPatientImages(
                ClinicalRecordsActor.from(jwt),
                patientId);
        return ResponseEntity.ok(ApiResponse.success(images));
    }

    @GetMapping("/patient/{patientId}/type/{imageType}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<DentalImageResponse>>> getPatientImagesByType(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId,
            @PathVariable String imageType) {
        List<DentalImageResponse> images = dentalImageService.getPatientImagesByType(
                ClinicalRecordsActor.from(jwt),
                patientId,
                imageType);
        return ResponseEntity.ok(ApiResponse.success(images));
    }

    @GetMapping("/patient/{patientId}/tooth/{toothNumber}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<DentalImageResponse>>> getPatientImagesByTooth(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId,
            @PathVariable String toothNumber) {
        List<DentalImageResponse> images = dentalImageService.getPatientImagesByTooth(
                ClinicalRecordsActor.from(jwt),
                patientId,
                toothNumber);
        return ResponseEntity.ok(ApiResponse.success(images));
    }

    @GetMapping("/patient/{patientId}/search")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<DentalImageResponse>>> searchPatientImagesByTag(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId,
            @RequestParam String tag) {
        List<DentalImageResponse> images = dentalImageService.searchPatientImagesByTag(
                ClinicalRecordsActor.from(jwt),
                patientId,
                tag);
        return ResponseEntity.ok(ApiResponse.success(images));
    }

    @GetMapping("/note/{clinicalNoteId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<DentalImageResponse>>> getClinicalNoteImages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long clinicalNoteId) {
        List<DentalImageResponse> images = dentalImageService.getClinicalNoteImages(
                ClinicalRecordsActor.from(jwt),
                clinicalNoteId);
        return ResponseEntity.ok(ApiResponse.success(images));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<DentalImageResponse>>> getVisitImages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long visitId) {
        List<DentalImageResponse> images = dentalImageService.getVisitImages(
                ClinicalRecordsActor.from(jwt),
                visitId);
        return ResponseEntity.ok(ApiResponse.success(images));
    }
}
