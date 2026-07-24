package press.mizhifei.dentist.userprofile.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import press.mizhifei.dentist.userprofile.dto.ApiResponse;
import press.mizhifei.dentist.userprofile.dto.PatientRequest;
import press.mizhifei.dentist.userprofile.dto.PatientResponse;
import press.mizhifei.dentist.userprofile.service.PatientService;

import java.util.List;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@RestController
@RequestMapping("/patient")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @PostMapping("/add")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'CLINIC_ADMIN', 'RECEPTIONIST')")
    public ResponseEntity<ApiResponse<PatientResponse>> createPatient(@RequestBody PatientRequest request) {
        PatientResponse patient = patientService.createPatient(request);
        return ResponseEntity.ok(ApiResponse.success(patient));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'CLINIC_ADMIN', 'RECEPTIONIST', 'DENTIST')")
    public ResponseEntity<ApiResponse<PatientResponse>> getPatientById(@PathVariable Long id) {
        PatientResponse patient = patientService.getPatientById(id);
        return ResponseEntity.ok(ApiResponse.success(patient));
    }

    @GetMapping("/list/all")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<PatientResponse>>> listAllPatients() {
        List<PatientResponse> patients = patientService.listAllPatients();
        return ResponseEntity.ok(ApiResponse.success(patients));
    }
}
