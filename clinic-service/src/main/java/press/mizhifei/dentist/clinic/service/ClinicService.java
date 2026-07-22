package press.mizhifei.dentist.clinic.service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.clinic.client.AppointmentServiceClient;
import press.mizhifei.dentist.clinic.client.AuthServiceClient;
import press.mizhifei.dentist.clinic.client.PatientServiceClient;
import press.mizhifei.dentist.clinic.dto.ClinicResponse;
import press.mizhifei.dentist.clinic.dto.ClinicSearchRequest;
import press.mizhifei.dentist.clinic.dto.ClinicCreateRequest;
import press.mizhifei.dentist.clinic.dto.ClinicUpdateRequest;
import press.mizhifei.dentist.clinic.dto.PatientResponse;
import press.mizhifei.dentist.clinic.dto.PatientWithAppointmentResponse;
import press.mizhifei.dentist.clinic.dto.UserResponse;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClinicService {

    private final ClinicRepository clinicRepository;
    private final AppointmentServiceClient appointmentServiceClient;
    private final PatientServiceClient patientServiceClient;
    private final AuthServiceClient authServiceClient;

    @Transactional(readOnly = true)
    public List<ClinicResponse> listAllEnabledClinics() {
        List<Clinic> clinics = clinicRepository.findByEnabledTrue();
        return clinics.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ClinicResponse getClinicById(Long id) {
        Clinic clinic = clinicRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Clinic not found with id: " + id));
        return convertToDto(clinic);
    }

    @Transactional(readOnly = true)
    public List<ClinicResponse> searchClinics(ClinicSearchRequest request) {
        // Sanitize and validate keywords
        String keywords = request.getKeywords();
        if (!StringUtils.hasText(keywords)) {
            // Return all enabled clinics if no keywords provided
            return listAllEnabledClinics();
        }
        
        // Trim whitespace and limit length as additional safety
        keywords = keywords.trim();
        if (keywords.length() > 100) {
            keywords = keywords.substring(0, 100);
        }
        
        List<Clinic> clinics = clinicRepository.searchClinics(keywords);
        return clinics.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    @Transactional
    public ClinicResponse createClinic(ClinicCreateRequest request) {
        Clinic clinic = Clinic.builder()
                .name(request.getName())
                .address(request.getAddress())
                .city(request.getCity())
                .state(request.getState())
                .zipCode(request.getZipCode())
                .country(request.getCountry())
                .phoneNumber(request.getPhoneNumber())
                .email(request.getEmail())
                .website(request.getWebsite())
                .enabled(false)
                .approved(false)
                .build();
        Clinic saved = clinicRepository.save(clinic);
        return convertToDto(saved);
    }

    @Transactional
    public ClinicResponse updateClinic(Long id, ClinicUpdateRequest request) {
        Clinic clinic = clinicRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Clinic not found with id: " + id));

        // Update only non-null fields
        if (request.getName() != null) {
            clinic.setName(request.getName());
        }
        if (request.getAddress() != null) {
            clinic.setAddress(request.getAddress());
        }
        if (request.getCity() != null) {
            clinic.setCity(request.getCity());
        }
        if (request.getState() != null) {
            clinic.setState(request.getState());
        }
        if (request.getZipCode() != null) {
            clinic.setZipCode(request.getZipCode());
        }
        if (request.getCountry() != null) {
            clinic.setCountry(request.getCountry());
        }
        if (request.getPhoneNumber() != null) {
            clinic.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getEmail() != null) {
            clinic.setEmail(request.getEmail());
        }

        Clinic saved = clinicRepository.save(clinic);
        log.info("Updated clinic {} with id {}", saved.getName(), id);
        return convertToDto(saved);
    }

    /**
     * Get patients for a clinic sorted by upcoming appointments
     * Primary sort: Patients with upcoming appointments first (sorted by next appointment date/time ascending)
     * Secondary sort: Patients without upcoming appointments last (sorted by last visit date descending)
     */
    @Transactional(readOnly = true)
    public List<PatientWithAppointmentResponse> getClinicPatientsSortedByAppointments(Long clinicId) {
        log.debug("Getting patients for clinic {} sorted by appointments", clinicId);

        // Verify clinic exists
        clinicRepository.findById(clinicId)
                .orElseThrow(() -> new IllegalArgumentException("Clinic not found with id: " + clinicId));

        // Get all patients who have appointments in this clinic from appointment service
        List<Long> patientIds;
        try {
            var response = appointmentServiceClient.getClinicPatientIds(clinicId);
            if (response.isSuccess() && response.getDataObject() != null) {
                patientIds = response.getDataObject();
            } else {
                log.warn("Failed to get patient IDs from appointment service for clinic {}: {}",
                        clinicId, response.getMessage());
                patientIds = new ArrayList<>();
            }
        } catch (Exception e) {
            rethrowAppointmentSecurityFailure(e);
            log.error("Error calling appointment service for clinic {}: {}", clinicId, e.getMessage());
            patientIds = new ArrayList<>();
        }
        log.debug("Found {} patients with appointments in clinic {}", patientIds.size(), clinicId);

        if (patientIds.isEmpty()) {
            return new ArrayList<>();
        }

        // Get patient details from patient service
        List<PatientResponse> patients = new ArrayList<>();
        for (Long patientId : patientIds) {
            try {
                PatientResponse patient = patientServiceClient.getPatientById(patientId);
                patients.add(patient);
            } catch (Exception e) {
                log.warn("Failed to fetch patient details for id {}: {}", patientId, e.getMessage());
            }
        }

        LocalDate currentDate = LocalDate.now();
        LocalTime currentTime = LocalTime.now();

        // Build response with appointment information
        List<PatientWithAppointmentResponse> result = new ArrayList<>();
        for (PatientResponse patient : patients) {
            PatientWithAppointmentResponse response = buildPatientWithAppointmentResponse(
                    patient, clinicId, currentDate, currentTime);
            result.add(response);
        }

        // Sort according to requirements
        result.sort(createPatientComparator());

        log.debug("Returning {} patients sorted by appointments for clinic {}", result.size(), clinicId);
        return result;
    }

    /**
     * Get dentists for a clinic
     * No role requirements - public endpoint
     */
    @Transactional(readOnly = true)
    public List<UserResponse> getClinicDentists(Long clinicId) {
        log.debug("Getting dentists for clinic {}", clinicId);

        // Verify clinic exists
        clinicRepository.findById(clinicId)
                .orElseThrow(() -> new IllegalArgumentException("Clinic not found with id: " + clinicId));

        try {
            List<UserResponse> dentists = authServiceClient.getClinicDentists(clinicId);
            log.debug("Found {} dentists for clinic {}", dentists.size(), clinicId);
            return dentists;
        } catch (Exception e) {
            log.error("Failed to fetch dentists for clinic {}: {}", clinicId, e.getMessage());
            throw new RuntimeException("Failed to fetch dentists for clinic", e);
        }
    }

    private PatientWithAppointmentResponse buildPatientWithAppointmentResponse(
            PatientResponse patient, Long clinicId, LocalDate currentDate, LocalTime currentTime) {

        // Get last completed appointment from appointment service
        LocalDate lastVisit = null;
        try {
            var lastResponse = appointmentServiceClient.getLastCompletedAppointment(
                    patient.getId(), clinicId, currentDate);
            if (lastResponse.isSuccess() && lastResponse.getDataObject() != null &&
                !lastResponse.getDataObject().isEmpty()) {
                lastVisit = lastResponse.getDataObject().get(0).getAppointmentDate();
            }
        } catch (Exception e) {
            rethrowAppointmentSecurityFailure(e);
            log.warn("Failed to get last appointment for patient {} in clinic {}: {}",
                    patient.getId(), clinicId, e.getMessage());
        }

        // Get next upcoming appointment from appointment service
        LocalDateTime nextAppointment = null;
        try {
            var upcomingResponse = appointmentServiceClient.getNextUpcomingAppointment(
                    patient.getId(), clinicId, currentDate, currentTime);
            if (upcomingResponse.isSuccess() && upcomingResponse.getDataObject() != null &&
                !upcomingResponse.getDataObject().isEmpty()) {
                var next = upcomingResponse.getDataObject().get(0);
                nextAppointment = LocalDateTime.of(next.getAppointmentDate(), next.getStartTime());
            }
        } catch (Exception e) {
            rethrowAppointmentSecurityFailure(e);
            log.warn("Failed to get upcoming appointment for patient {} in clinic {}: {}",
                    patient.getId(), clinicId, e.getMessage());
        }

        return PatientWithAppointmentResponse.builder()
                .id(patient.getId())
                .name(patient.getFirstName() + " " + patient.getLastName())
                .phone(patient.getPhoneNumber())
                .email(patient.getEmail())
                .address(patient.getAddress())
                .dateOfBirth(patient.getDateOfBirth())
                .healthHistory(patient.getHealthHistory())
                .lastVisit(lastVisit)
                .nextAppointment(nextAppointment)
                .build();
    }

    private void rethrowAppointmentSecurityFailure(Exception exception) {
        if (exception instanceof FeignException feignException
                && (feignException.status() == 401
                || feignException.status() == 403
                || feignException.status() == 503)) {
            throw feignException;
        }
    }

    private Comparator<PatientWithAppointmentResponse> createPatientComparator() {
        return (p1, p2) -> {
            // Primary sort: Patients with upcoming appointments first
            boolean p1HasNext = p1.getNextAppointment() != null;
            boolean p2HasNext = p2.getNextAppointment() != null;

            if (p1HasNext && !p2HasNext) {
                return -1; // p1 comes first
            } else if (!p1HasNext && p2HasNext) {
                return 1; // p2 comes first
            } else if (p1HasNext && p2HasNext) {
                // Both have upcoming appointments, sort by next appointment date/time ascending
                return p1.getNextAppointment().compareTo(p2.getNextAppointment());
            } else {
                // Neither has upcoming appointments, sort by last visit date descending
                if (p1.getLastVisit() == null && p2.getLastVisit() == null) {
                    return 0;
                } else if (p1.getLastVisit() == null) {
                    return 1; // p2 comes first
                } else if (p2.getLastVisit() == null) {
                    return -1; // p1 comes first
                } else {
                    return p2.getLastVisit().compareTo(p1.getLastVisit()); // Descending order
                }
            }
        };
    }

    private ClinicResponse convertToDto(Clinic clinic) {
        return ClinicResponse.builder()
                .id(clinic.getId())
                .name(clinic.getName())
                .address(clinic.getAddress())
                .city(clinic.getCity())
                .state(clinic.getState())
                .zipCode(clinic.getZipCode())
                .country(clinic.getCountry())
                .phoneNumber(clinic.getPhoneNumber())
                .email(clinic.getEmail())
                .website(clinic.getWebsite())
                .build();
    }
}