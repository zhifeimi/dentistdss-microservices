package press.mizhifei.dentist.clinicalrecords.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.clinicalrecords.client.AuthServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.ClinicServiceClient;
import press.mizhifei.dentist.clinicalrecords.dto.ServiceVisitRequest;
import press.mizhifei.dentist.clinicalrecords.dto.ServiceVisitResponse;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalResourceNotFoundException;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalStateConflictException;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;
import press.mizhifei.dentist.clinicalrecords.model.ServiceVisit;
import press.mizhifei.dentist.clinicalrecords.repository.ServiceVisitRepository;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsAccess;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceVisitService {

    private final ServiceVisitRepository serviceVisitRepository;
    private final AuthServiceClient authServiceClient;
    private final ClinicServiceClient clinicServiceClient;

    @Transactional
    public ServiceVisitResponse createServiceVisit(
            ClinicalRecordsActor actor,
            ServiceVisitRequest request) {
        ClinicalRecordsAccess.WriteOwner owner = ClinicalRecordsAccess.resolveWriteOwner(
                actor,
                request.getPatientId(),
                request.getDentistId(),
                request.getClinicId());
        validateVisitTimes(request.getCheckInTime(), request.getCheckOutTime());

        ServiceVisit serviceVisit = ServiceVisit.builder()
                .patientId(owner.patientId())
                .dentistId(owner.dentistId())
                .clinicId(owner.clinicId())
                .appointmentId(request.getAppointmentId())
                .visitType(request.getVisitType())
                .visitDate(request.getVisitDate())
                .checkInTime(request.getCheckInTime())
                .checkOutTime(request.getCheckOutTime())
                .notes(request.getNotes())
                .build();

        if (request.getCheckInTime() != null && request.getCheckOutTime() != null) {
            Duration duration = Duration.between(request.getCheckInTime(), request.getCheckOutTime());
            serviceVisit.setDurationMinutes(Math.toIntExact(duration.toMinutes()));
            serviceVisit.setStatus("COMPLETED");
        }

        ServiceVisit saved = serviceVisitRepository.save(serviceVisit);
        log.info("Created service visit {}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public ServiceVisitResponse checkInVisit(ClinicalRecordsActor actor, Long visitId) {
        ServiceVisit visit = findManageableVisit(actor, visitId);
        if (visit.getCheckInTime() != null) {
            throw new ClinicalStateConflictException();
        }

        visit.setCheckInTime(LocalDateTime.now());
        visit.setStatus("IN_PROGRESS");
        ServiceVisit saved = serviceVisitRepository.save(visit);
        log.info("Checked in service visit {}", visitId);
        return toResponse(saved);
    }

    @Transactional
    public ServiceVisitResponse checkOutVisit(ClinicalRecordsActor actor, Long visitId) {
        ServiceVisit visit = findManageableVisit(actor, visitId);
        if (visit.getCheckInTime() == null || visit.getCheckOutTime() != null) {
            throw new ClinicalStateConflictException();
        }

        LocalDateTime checkOutTime = LocalDateTime.now();
        visit.setCheckOutTime(checkOutTime);
        visit.setStatus("COMPLETED");
        visit.setDurationMinutes(Math.toIntExact(Duration.between(visit.getCheckInTime(), checkOutTime).toMinutes()));

        ServiceVisit saved = serviceVisitRepository.save(visit);
        log.info("Checked out service visit {}", visitId);
        return toResponse(saved);
    }

    @Transactional
    public ServiceVisitResponse updateVisitNotes(
            ClinicalRecordsActor actor,
            Long visitId,
            String notes) {
        ServiceVisit visit = findManageableVisit(actor, visitId);
        visit.setNotes(notes);
        ServiceVisit saved = serviceVisitRepository.save(visit);
        log.info("Updated service visit {} notes", visitId);
        return toResponse(saved);
    }

    @Transactional
    public ServiceVisitResponse cancelVisit(ClinicalRecordsActor actor, Long visitId) {
        ServiceVisit visit = findManageableVisit(actor, visitId);
        if ("COMPLETED".equals(visit.getStatus())) {
            throw new ClinicalStateConflictException();
        }

        visit.setStatus("CANCELLED");
        ServiceVisit saved = serviceVisitRepository.save(visit);
        log.info("Cancelled service visit {}", visitId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ServiceVisitResponse getServiceVisit(ClinicalRecordsActor actor, Long visitId) {
        return toResponse(findReadableVisit(actor, visitId));
    }

    @Transactional(readOnly = true)
    public List<ServiceVisitResponse> getPatientVisits(ClinicalRecordsActor actor, Long patientId) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        List<ServiceVisit> visits;
        if (actor.isSystemAdmin()) {
            visits = serviceVisitRepository.findByPatientIdOrderByVisitDateDesc(targetPatientId);
        } else if (ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            visits = serviceVisitRepository.findByPatientIdOrderByVisitDateDesc(targetPatientId);
        } else if (actor.isDentist()) {
            visits = serviceVisitRepository.findByPatientIdAndDentistIdAndClinicIdOrderByVisitDateDesc(
                    targetPatientId,
                    actor.userId(),
                    actor.requiredClinicId());
        } else {
            throw new AccessDeniedException("Clinical read access is unavailable");
        }
        return visits.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceVisitResponse> getDentistVisits(ClinicalRecordsActor actor, Long dentistId) {
        long targetDentistId = ClinicalRecordsAccess.requirePositive(dentistId);
        List<ServiceVisit> visits;
        if (actor.isSystemAdmin()) {
            visits = serviceVisitRepository.findByDentistIdOrderByVisitDateDesc(targetDentistId);
        } else if (actor.isDentist() && actor.userId() == targetDentistId) {
            visits = serviceVisitRepository.findByDentistIdAndClinicIdOrderByVisitDateDesc(
                    targetDentistId,
                    actor.requiredClinicId());
        } else if (actor.isDentist()) {
            throw new ClinicalResourceNotFoundException();
        } else {
            throw new AccessDeniedException("Clinical read access is unavailable");
        }
        return visits.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceVisitResponse> getClinicVisits(ClinicalRecordsActor actor, Long clinicId) {
        requireSystemAdmin(actor);
        return serviceVisitRepository.findByClinicIdOrderByVisitDateDesc(
                        ClinicalRecordsAccess.requirePositive(clinicId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceVisitResponse getVisitByAppointment(
            ClinicalRecordsActor actor,
            Long appointmentId) {
        long targetAppointmentId = ClinicalRecordsAccess.requirePositive(appointmentId);
        Optional<ServiceVisit> visit;
        if (actor.isSystemAdmin()) {
            visit = serviceVisitRepository.findByAppointmentId(targetAppointmentId);
        } else {
            visit = Optional.empty();
            if (actor.isPatient()) {
                visit = serviceVisitRepository.findByAppointmentIdAndPatientId(targetAppointmentId, actor.userId());
            }
            if (visit.isEmpty() && actor.isDentist()) {
                visit = serviceVisitRepository.findByAppointmentIdAndDentistIdAndClinicId(
                        targetAppointmentId,
                        actor.userId(),
                        actor.requiredClinicId());
            }
            if (!actor.isPatient() && !actor.isDentist()) {
                throw new AccessDeniedException("Clinical read access is unavailable");
            }
        }
        return toResponse(visit.orElseThrow(ClinicalResourceNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public List<ServiceVisitResponse> getPatientVisitsByDateRange(
            ClinicalRecordsActor actor,
            Long patientId,
            LocalDateTime startDate,
            LocalDateTime endDate) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        validateDateRange(startDate, endDate);
        List<ServiceVisit> visits;
        if (actor.isSystemAdmin() || ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            visits = serviceVisitRepository.findByPatientIdAndDateRange(targetPatientId, startDate, endDate);
        } else if (actor.isDentist()) {
            visits = serviceVisitRepository.findByPatientIdAndDentistIdAndClinicIdAndDateRange(
                    targetPatientId,
                    actor.userId(),
                    actor.requiredClinicId(),
                    startDate,
                    endDate);
        } else {
            throw new AccessDeniedException("Clinical read access is unavailable");
        }
        return visits.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceVisitResponse> getClinicVisitsByStatus(
            ClinicalRecordsActor actor,
            Long clinicId,
            String status) {
        requireSystemAdmin(actor);
        if (status == null || status.isBlank()) {
            throw new InvalidClinicalRequestException();
        }
        return serviceVisitRepository.findByClinicIdAndStatus(
                        ClinicalRecordsAccess.requirePositive(clinicId),
                        status)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private ServiceVisit findReadableVisit(ClinicalRecordsActor actor, Long visitId) {
        long targetVisitId = ClinicalRecordsAccess.requirePositive(visitId);
        Optional<ServiceVisit> visit;
        if (actor.isSystemAdmin()) {
            visit = serviceVisitRepository.findById(targetVisitId);
        } else {
            visit = Optional.empty();
            if (actor.isPatient()) {
                visit = serviceVisitRepository.findByIdAndPatientId(targetVisitId, actor.userId());
            }
            if (visit.isEmpty() && actor.isDentist()) {
                visit = serviceVisitRepository.findByIdAndDentistIdAndClinicId(
                        targetVisitId,
                        actor.userId(),
                        actor.requiredClinicId());
            }
            if (!actor.isPatient() && !actor.isDentist()) {
                throw new AccessDeniedException("Clinical read access is unavailable");
            }
        }
        return visit.orElseThrow(ClinicalResourceNotFoundException::new);
    }

    private ServiceVisit findManageableVisit(ClinicalRecordsActor actor, Long visitId) {
        long targetVisitId = ClinicalRecordsAccess.requirePositive(visitId);
        ClinicalRecordsAccess.requireClinicalManager(actor);
        Optional<ServiceVisit> visit = actor.isSystemAdmin()
                ? serviceVisitRepository.findById(targetVisitId)
                : serviceVisitRepository.findByIdAndDentistIdAndClinicId(
                        targetVisitId,
                        actor.userId(),
                        actor.requiredClinicId());
        return visit.orElseThrow(ClinicalResourceNotFoundException::new);
    }

    private void requireSystemAdmin(ClinicalRecordsActor actor) {
        if (!actor.isSystemAdmin()) {
            throw new AccessDeniedException("Clinical administrative access is unavailable");
        }
    }

    private void validateVisitTimes(LocalDateTime checkInTime, LocalDateTime checkOutTime) {
        if (checkOutTime != null && checkInTime == null
                || checkInTime != null && checkOutTime != null && checkOutTime.isBefore(checkInTime)) {
            throw new InvalidClinicalRequestException();
        }
    }

    private void validateDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new InvalidClinicalRequestException();
        }
    }

    private ServiceVisitResponse toResponse(ServiceVisit visit) {
        ServiceVisitResponse response = ServiceVisitResponse.builder()
                .id(visit.getId())
                .patientId(visit.getPatientId())
                .dentistId(visit.getDentistId())
                .clinicId(visit.getClinicId())
                .appointmentId(visit.getAppointmentId())
                .visitType(visit.getVisitType())
                .visitDate(visit.getVisitDate())
                .checkInTime(visit.getCheckInTime())
                .checkOutTime(visit.getCheckOutTime())
                .durationMinutes(visit.getDurationMinutes())
                .status(visit.getStatus())
                .notes(visit.getNotes())
                .createdAt(visit.getCreatedAt())
                .updatedAt(visit.getUpdatedAt())
                .build();

        try {
            response.setPatientName(authServiceClient.getUserFullName(visit.getPatientId()));
        } catch (Exception exception) {
            log.warn("Failed to enrich service visit {} from a dependent service", visit.getId());
        }
        try {
            response.setDentistName(authServiceClient.getUserFullName(visit.getDentistId()));
        } catch (Exception exception) {
            log.warn("Failed to enrich service visit {} from a dependent service", visit.getId());
        }
        try {
            response.setClinicName(clinicServiceClient.getClinic(visit.getClinicId()).getName());
        } catch (Exception exception) {
            log.warn("Failed to enrich service visit {} from a dependent service", visit.getId());
        }
        return response;
    }
}
