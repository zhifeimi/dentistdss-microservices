package press.mizhifei.dentist.clinic.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import press.mizhifei.dentist.clinic.dto.ApiResponse;
import press.mizhifei.dentist.clinic.dto.HolidayRequest;
import press.mizhifei.dentist.clinic.dto.HolidayResponse;
import press.mizhifei.dentist.clinic.model.ClinicHoliday;
import press.mizhifei.dentist.clinic.repository.ClinicHolidayRepository;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.security.AuthenticatedUser;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Holidays and one-off closures for the dashboard's Holidays page. Only the
 * clinic's own admin (or the system admin) can read or change them.
 */
@RestController
@RequestMapping("/clinic/{clinicId}/holidays")
@PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class HolidayController {

    // ponytail: one zone for "today"; per-clinic time zones if clinics outside AEST/AEDT need exact cut-overs.
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Australia/Sydney");

    private final ClinicHolidayRepository holidayRepository;
    private final ClinicRepository clinicRepository;
    private final Clock clock;

    @GetMapping
    public ResponseEntity<ApiResponse<List<HolidayResponse>>> list(
            @PathVariable Long clinicId, @AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<ApiResponse<List<HolidayResponse>>> denied = requireOwnClinic(clinicId, jwt);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(ApiResponse.success(responses(clinicId).toList()));
    }

    @GetMapping("/upcoming")
    public ResponseEntity<ApiResponse<List<HolidayResponse>>> upcoming(
            @PathVariable Long clinicId, @AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<ApiResponse<List<HolidayResponse>>> denied = requireOwnClinic(clinicId, jwt);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(ApiResponse.success(responses(clinicId)
                .filter(HolidayResponse::isUpcoming)
                .sorted(Comparator.comparing(HolidayResponse::holidayDate))
                .toList()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<HolidayResponse>> create(
            @PathVariable Long clinicId,
            @Valid @RequestBody HolidayRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<ApiResponse<HolidayResponse>> denied = requireOwnClinic(clinicId, jwt);
        if (denied != null) {
            return denied;
        }
        if (!clinicRepository.existsById(clinicId)) {
            return ResponseEntity.status(404).body(ApiResponse.error("Clinic not found"));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ClinicHoliday holiday = ClinicHoliday.builder().clinicId(clinicId).createdAt(now).build();
        return save(holiday, request, now);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<HolidayResponse>> update(
            @PathVariable Long clinicId,
            @PathVariable Long id,
            @Valid @RequestBody HolidayRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<ApiResponse<HolidayResponse>> denied = requireOwnClinic(clinicId, jwt);
        if (denied != null) {
            return denied;
        }
        ClinicHoliday holiday = holidayRepository.findById(id)
                .filter(h -> h.getClinicId().equals(clinicId))
                .orElse(null);
        if (holiday == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("Holiday not found"));
        }
        return save(holiday, request, LocalDateTime.now(clock));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long clinicId,
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<ApiResponse<Void>> denied = requireOwnClinic(clinicId, jwt);
        if (denied != null) {
            return denied;
        }
        ClinicHoliday holiday = holidayRepository.findById(id)
                .filter(h -> h.getClinicId().equals(clinicId))
                .orElse(null);
        if (holiday == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("Holiday not found"));
        }
        holidayRepository.delete(holiday);
        return ResponseEntity.ok(ApiResponse.successMessage("Holiday deleted"));
    }

    private ResponseEntity<ApiResponse<HolidayResponse>> save(
            ClinicHoliday holiday, HolidayRequest request, LocalDateTime now) {
        boolean fullDay = request.isFullDayClosure();
        holiday.setName(request.name().trim());
        holiday.setHolidayDate(request.holidayDate());
        holiday.setDescription(blankToNull(request.description()));
        holiday.setType(request.type());
        holiday.setIsFullDayClosure(fullDay);
        holiday.setSpecialOpeningTime(fullDay ? null : LocalTime.parse(request.specialOpeningTime()));
        holiday.setSpecialClosingTime(fullDay ? null : LocalTime.parse(request.specialClosingTime()));
        holiday.setIsRecurring(request.isRecurring());
        holiday.setEmergencyContact(blankToNull(request.emergencyContact()));
        holiday.setUpdatedAt(now);
        try {
            ClinicHoliday saved = holidayRepository.saveAndFlush(holiday);
            return ResponseEntity.ok(ApiResponse.success(HolidayResponse.of(saved, today())));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409)
                    .body(ApiResponse.error("This clinic already has a holiday on that date."));
        }
    }

    private java.util.stream.Stream<HolidayResponse> responses(Long clinicId) {
        LocalDate today = today();
        return holidayRepository.findByClinicIdOrderByHolidayDateAsc(clinicId).stream()
                .map(h -> HolidayResponse.of(h, today));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(CLINIC_ZONE));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A clinic admin may only manage their own clinic; the system admin is unrestricted. */
    private static <T> ResponseEntity<ApiResponse<T>> requireOwnClinic(Long clinicId, Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        if (user.hasRole("SYSTEM_ADMIN") || Objects.equals(user.clinicId(), clinicId)) {
            return null;
        }
        return ResponseEntity.status(403)
                .body(ApiResponse.error("Access denied. You can only manage your own clinic's holidays."));
    }
}
