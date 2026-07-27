package press.mizhifei.dentist.appointment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import press.mizhifei.dentist.appointment.dto.ApiResponse;
import press.mizhifei.dentist.appointment.dto.AppointmentRequest;
import press.mizhifei.dentist.appointment.dto.AppointmentResponse;
import press.mizhifei.dentist.appointment.dto.AvailableSlotResponse;
import press.mizhifei.dentist.appointment.dto.CancelAppointmentRequest;
import press.mizhifei.dentist.appointment.dto.RescheduleAppointmentRequest;
import press.mizhifei.dentist.appointment.security.AppointmentActor;
import press.mizhifei.dentist.appointment.service.AppointmentService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/appointment")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping("/create")
    @PreAuthorize("hasAnyRole('PATIENT', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> createAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AppointmentRequest request) {
        AppointmentResponse response = appointmentService.createAppointment(
                request,
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> confirmAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        AppointmentResponse response = appointmentService.confirmAppointment(
                id,
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> cancelAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody CancelAppointmentRequest request) {
        AppointmentResponse response = appointmentService.cancelAppointment(
                id,
                request.reason(),
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/reschedule")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> rescheduleAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody RescheduleAppointmentRequest request) {
        AppointmentResponse response = appointmentService.rescheduleAppointment(
                id,
                request.newDate(),
                request.newStartTime(),
                request.newEndTime(),
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> completeAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        AppointmentResponse response = appointmentService.completeAppointment(
                id,
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/no-show")
    @PreAuthorize("hasAnyRole('DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> markNoShow(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        AppointmentResponse response = appointmentService.markNoShow(
                id,
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<AppointmentResponse>>> getPatientAppointments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId) {
        List<AppointmentResponse> appointments = appointmentService.getPatientAppointments(
                patientId,
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }

    @GetMapping("/dentist/{dentistId}")
    @PreAuthorize("hasAnyRole('DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<AppointmentResponse>>> getDentistAppointments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long dentistId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<AppointmentResponse> appointments = appointmentService.getDentistAppointments(
                dentistId,
                date,
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }

    @GetMapping("/clinic/{clinicId}")
    @PreAuthorize("hasAnyRole('DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<AppointmentResponse>>> getClinicAppointments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long clinicId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<AppointmentResponse> appointments = appointmentService.getClinicAppointments(
                clinicId,
                date,
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }

    @GetMapping("/available-slots")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AvailableSlotResponse>>> getAvailableSlots(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam Long dentistId,
            @RequestParam Long clinicId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer serviceId,
            @RequestParam(defaultValue = "30") Integer serviceDurationMinutes) {
        List<AvailableSlotResponse> slots = appointmentService.getAvailableSlots(
                dentistId,
                clinicId,
                date,
                serviceId,
                serviceDurationMinutes,
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(slots));
    }

    @GetMapping("/patient/{patientId}/clinic/{clinicId}/last-completed")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<AppointmentResponse>>> getLastCompletedAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId,
            @PathVariable Long clinicId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentDate) {
        List<AppointmentResponse> appointments = appointmentService
                .getLastCompletedAppointmentByPatientAndClinic(
                        patientId,
                        clinicId,
                        currentDate,
                        AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }

    @GetMapping("/patient/{patientId}/clinic/{clinicId}/next-upcoming")
    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<AppointmentResponse>>> getNextUpcomingAppointment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long patientId,
            @PathVariable Long clinicId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate currentDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime currentTime) {
        List<AppointmentResponse> appointments = appointmentService
                .getNextUpcomingAppointmentByPatientAndClinic(
                        patientId,
                        clinicId,
                        currentDate,
                        currentTime,
                        AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }

    @GetMapping("/clinic/{clinicId}/patients")
    @PreAuthorize("hasAnyRole('RECEPTIONIST', 'CLINIC_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<Long>>> getClinicPatientIds(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long clinicId) {
        List<Long> patientIds = appointmentService.getDistinctPatientIdsByClinicId(
                clinicId,
                AppointmentActor.from(jwt));
        return ResponseEntity.ok(ApiResponse.success(patientIds));
    }
}
