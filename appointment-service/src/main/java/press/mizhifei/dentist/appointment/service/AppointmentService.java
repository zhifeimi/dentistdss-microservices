package press.mizhifei.dentist.appointment.service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.appointment.client.UserProfileServiceClient;
import press.mizhifei.dentist.appointment.client.ClinicServiceClient;
import press.mizhifei.dentist.appointment.client.NotificationClient;
import press.mizhifei.dentist.appointment.client.ServiceResponse;
import press.mizhifei.dentist.appointment.dto.ApiResponse;
import press.mizhifei.dentist.appointment.dto.AppointmentRequest;
import press.mizhifei.dentist.appointment.dto.AppointmentResponse;
import press.mizhifei.dentist.appointment.dto.AvailableSlotResponse;
import press.mizhifei.dentist.appointment.exception.AppointmentConflictException;
import press.mizhifei.dentist.appointment.exception.AppointmentDependencyUnavailableException;
import press.mizhifei.dentist.appointment.exception.AppointmentNotFoundException;
import press.mizhifei.dentist.appointment.exception.InvalidAppointmentRequestException;
import press.mizhifei.dentist.appointment.model.Appointment;
import press.mizhifei.dentist.appointment.model.AppointmentStatus;
import press.mizhifei.dentist.appointment.model.DentistAvailability;
import press.mizhifei.dentist.appointment.model.UrgencyLevel;
import press.mizhifei.dentist.appointment.repository.AppointmentRepository;
import press.mizhifei.dentist.appointment.repository.DentistAvailabilityRepository;
import press.mizhifei.dentist.appointment.security.AppointmentActor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private static final int MAX_SLOT_DURATION_MINUTES = 480;
    private static final int DEFAULT_SERVICELESS_APPOINTMENT_DURATION_MINUTES = 30;
    private static final List<AppointmentStatus> NON_BLOCKING_STATUSES = List.of(
            AppointmentStatus.CANCELLED,
            AppointmentStatus.NO_SHOW);

    private final AppointmentRepository appointmentRepository;
    private final DentistAvailabilityRepository availabilityRepository;
    private final NotificationClient notificationClient;
    private final UserProfileServiceClient userProfileServiceClient;
    private final ClinicServiceClient clinicServiceClient;

    @Transactional
    public AppointmentResponse createAppointment(
            AppointmentRequest request,
            AppointmentActor actor) {
        validateTimeRange(
                request.getAppointmentDate(),
                request.getStartTime(),
                request.getEndTime());
        requireFutureStart(
                request.getAppointmentDate(),
                request.getStartTime());

        long patientId = resolveCreatePatient(request, actor);
        requireDentistAvailability(
                request.getDentistId(),
                request.getClinicId(),
                request.getAppointmentDate(),
                request.getStartTime(),
                request.getEndTime());
        ServiceResponse service = validateService(
                request.getServiceId(),
                request.getClinicId());
        requireServiceDuration(
                service,
                request.getStartTime(),
                request.getEndTime());
        appointmentRepository.acquireDentistScheduleLock(request.getDentistId());

        if (!appointmentRepository.findConflictingAppointments(
                        request.getDentistId(),
                        request.getAppointmentDate(),
                        request.getStartTime(),
                        request.getEndTime(),
                        NON_BLOCKING_STATUSES)
                .isEmpty()) {
            throw new AppointmentConflictException(
                    "The requested time slot is unavailable");
        }

        Appointment saved = appointmentRepository.saveWithCasting(
                patientId,
                request.getDentistId(),
                request.getClinicId(),
                request.getServiceId(),
                request.getAppointmentDate(),
                request.getStartTime(),
                request.getEndTime(),
                AppointmentStatus.REQUESTED.name(),
                request.getReasonForVisit(),
                request.getSymptoms(),
                parseUrgencyLevel(request.getUrgencyLevel()).name(),
                null,
                request.getNotes(),
                actor.userId());
        log.info(
                "Created appointment {} for patient {} with dentist {}",
                saved.getId(),
                saved.getPatientId(),
                saved.getDentistId());
        return toResponse(saved, true);
    }

    @Transactional
    public AppointmentResponse confirmAppointment(
            Long appointmentId,
            AppointmentActor actor) {
        Appointment authorized = findAuthorizedAppointment(
                appointmentId,
                actor,
                false,
                true,
                true);
        Appointment saved = runTransition(
                () -> appointmentRepository.confirmAppointment(
                        authorized.getId(),
                        actor.userId()),
                "Appointment cannot be confirmed in its current state");
        log.info("Confirmed appointment {} by user {}", appointmentId, actor.userId());
        try {
            sendAppointmentNotification(saved, "appointment_confirmation");
        } catch (Exception exception) {
            log.warn("Appointment confirmation notification was not delivered");
        }
        return toResponse(saved, mayViewClinicalDetails(saved, actor));
    }

    @Transactional
    public AppointmentResponse cancelAppointment(
            Long appointmentId,
            String reason,
            AppointmentActor actor) {
        Appointment authorized = findAuthorizedAppointment(
                appointmentId,
                actor,
                true,
                true,
                true);
        Appointment saved = runTransition(
                () -> appointmentRepository.cancelAppointment(
                        authorized.getId(),
                        reason,
                        actor.userId()),
                "Appointment cannot be cancelled in its current state");
        log.info("Cancelled appointment {} by user {}", appointmentId, actor.userId());
        try {
            sendCancellationNotification(authorized, reason);
        } catch (Exception exception) {
            log.warn("Appointment cancellation notification was not delivered");
        }
        return toResponse(saved, mayViewClinicalDetails(saved, actor));
    }

    @Transactional
    public AppointmentResponse rescheduleAppointment(
            Long appointmentId,
            LocalDate newDate,
            LocalTime newStartTime,
            LocalTime newEndTime,
            AppointmentActor actor) {
        validateTimeRange(newDate, newStartTime, newEndTime);
        requireFutureStart(newDate, newStartTime);

        Appointment authorized = findAuthorizedAppointment(
                appointmentId,
                actor,
                true,
                true,
                true);
        requireDentistAvailability(
                authorized.getDentistId(),
                authorized.getClinicId(),
                newDate,
                newStartTime,
                newEndTime);
        ServiceResponse service = validateService(
                authorized.getServiceId(),
                authorized.getClinicId());
        requireServiceDuration(service, newStartTime, newEndTime);
        appointmentRepository.acquireDentistScheduleLock(authorized.getDentistId());

        boolean hasConflict = appointmentRepository.findConflictingAppointments(
                        authorized.getDentistId(),
                        newDate,
                        newStartTime,
                        newEndTime,
                        NON_BLOCKING_STATUSES)
                .stream()
                .anyMatch(appointment -> !appointment.getId().equals(appointmentId));
        if (hasConflict) {
            throw new AppointmentConflictException(
                    "The requested time slot is unavailable");
        }

        Appointment saved = runTransition(
                () -> appointmentRepository.rescheduleAppointment(
                        authorized.getId(),
                        newDate,
                        newStartTime,
                        newEndTime),
                "Appointment cannot be rescheduled in its current state");
        log.info("Rescheduled appointment {} by user {}", appointmentId, actor.userId());
        return toResponse(saved, mayViewClinicalDetails(saved, actor));
    }

    @Transactional
    public AppointmentResponse completeAppointment(
            Long appointmentId,
            AppointmentActor actor) {
        Appointment authorized = findAuthorizedAppointment(
                appointmentId,
                actor,
                false,
                true,
                true);
        Appointment saved = runTransition(
                () -> appointmentRepository.completeAppointment(authorized.getId()),
                "Appointment cannot be completed in its current state");
        return toResponse(saved, mayViewClinicalDetails(saved, actor));
    }

    @Transactional
    public AppointmentResponse markNoShow(
            Long appointmentId,
            AppointmentActor actor) {
        Appointment authorized = findAuthorizedAppointment(
                appointmentId,
                actor,
                false,
                true,
                true);
        if (LocalDateTime.of(
                        authorized.getAppointmentDate(),
                        authorized.getStartTime())
                .isAfter(LocalDateTime.now())) {
            throw new AppointmentConflictException(
                    "Appointment cannot be marked as no-show before it starts");
        }
        Appointment saved = runTransition(
                () -> appointmentRepository.markNoShow(authorized.getId()),
                "Appointment cannot be marked as no-show in its current state");
        return toResponse(saved, mayViewClinicalDetails(saved, actor));
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getPatientAppointments(
            Long patientId,
            AppointmentActor actor) {
        List<Appointment> appointments;
        boolean includeClinicalDetails;
        if (actor.isSystemAdmin()) {
            appointments = appointmentRepository
                    .findByPatientIdOrderByAppointmentDateDescStartTimeDesc(patientId);
            includeClinicalDetails = true;
        } else if (actor.isPatient()) {
            if (actor.userId() != patientId) {
                throw new AppointmentNotFoundException();
            }
            appointments = appointmentRepository
                    .findByPatientIdOrderByAppointmentDateDescStartTimeDesc(patientId);
            includeClinicalDetails = true;
        } else if (actor.isDentist()) {
            appointments = appointmentRepository
                    .findByPatientIdAndDentistIdOrderByAppointmentDateDescStartTimeDesc(
                            patientId,
                            actor.userId());
            includeClinicalDetails = true;
        } else if (actor.isClinicStaff()) {
            appointments = appointmentRepository
                    .findByPatientIdAndClinicIdOrderByAppointmentDateDescStartTimeDesc(
                            patientId,
                            actor.requiredClinicId());
            includeClinicalDetails = false;
        } else {
            throw new AccessDeniedException("Appointment access is denied");
        }
        return mapResponses(appointments, includeClinicalDetails);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getDentistAppointments(
            Long dentistId,
            LocalDate date,
            AppointmentActor actor) {
        List<Appointment> appointments;
        boolean includeClinicalDetails;
        if (actor.isSystemAdmin()) {
            appointments = appointmentRepository
                    .findByDentistIdAndAppointmentDateOrderByStartTime(dentistId, date);
            includeClinicalDetails = true;
        } else if (actor.isDentist()) {
            if (actor.userId() != dentistId) {
                throw new AppointmentNotFoundException();
            }
            appointments = appointmentRepository
                    .findByDentistIdAndAppointmentDateOrderByStartTime(dentistId, date);
            includeClinicalDetails = true;
        } else if (actor.isClinicStaff()) {
            appointments = appointmentRepository
                    .findByDentistIdAndClinicIdAndAppointmentDateOrderByStartTime(
                            dentistId,
                            actor.requiredClinicId(),
                            date);
            includeClinicalDetails = false;
        } else {
            throw new AccessDeniedException("Appointment access is denied");
        }
        return mapResponses(appointments, includeClinicalDetails);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getClinicAppointments(
            Long clinicId,
            LocalDate date,
            AppointmentActor actor) {
        if (!actor.isSystemAdmin() && actor.requiredClinicId() != clinicId) {
            throw new AppointmentNotFoundException();
        }
        List<Appointment> appointments = appointmentRepository
                .findByClinicIdAndAppointmentDateOrderByStartTime(clinicId, date);
        return mapResponses(appointments, actor.isSystemAdmin());
    }

    @Transactional(readOnly = true)
    public List<AvailableSlotResponse> getAvailableSlots(
            Long dentistId,
            Long clinicId,
            LocalDate date,
            Integer serviceId,
            Integer serviceDurationMinutes,
            AppointmentActor actor) {
        if (!actor.isSystemAdmin()
                && (actor.isDentist() || actor.isClinicStaff())
                && actor.requiredClinicId() != clinicId) {
            throw new AppointmentNotFoundException();
        }
        ServiceResponse service = validateService(serviceId, clinicId);
        int slotDurationMinutes = service == null
                ? requireServicelessAppointmentDuration(serviceDurationMinutes)
                : requireValidSlotDuration(service.getDurationMinutes());
        if (date.isBefore(LocalDate.now())) {
            throw new InvalidAppointmentRequestException(
                    "Appointment date must not be in the past");
        }

        List<DentistAvailability> availabilities = availabilityRepository
                .findAvailableSlots(dentistId, clinicId, date);
        if (availabilities.isEmpty()) {
            return List.of();
        }
        List<Appointment> existingAppointments = appointmentRepository
                .findSlotBlockingAppointments(
                        dentistId,
                        date,
                        NON_BLOCKING_STATUSES);
        List<AvailableSlotResponse> availableSlots = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (DentistAvailability availability : availabilities) {
            LocalTime availabilityStart = availability.getStartTime();
            LocalTime availabilityEnd = availability.getEndTime();
            if (availabilityStart == null
                    || availabilityEnd == null
                    || !availabilityStart.isBefore(availabilityEnd)) {
                continue;
            }
            LocalTime currentTime = availabilityStart;
            while (Duration.between(currentTime, availabilityEnd).toMinutes()
                    >= slotDurationMinutes) {
                LocalTime slotStartTime = currentTime;
                LocalTime slotEndTime = currentTime.plusMinutes(
                        slotDurationMinutes);
                boolean future = LocalDateTime.of(date, slotStartTime)
                        .isAfter(now);
                boolean available = future && existingAppointments.stream()
                        .noneMatch(appointment -> doesTimeOverlap(
                                slotStartTime,
                                slotEndTime,
                                appointment.getStartTime(),
                                appointment.getEndTime()));
                if (available) {
                    availableSlots.add(AvailableSlotResponse.builder()
                            .date(date)
                            .startTime(slotStartTime)
                            .endTime(slotEndTime)
                            .dentistId(dentistId)
                            .clinicId(clinicId)
                            .available(true)
                            .build());
                }
                currentTime = currentTime.plusMinutes(15);
            }
        }
        return availableSlots;
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getLastCompletedAppointmentByPatientAndClinic(
            Long patientId,
            Long clinicId,
            LocalDate currentDate,
            AppointmentActor actor) {
        List<Appointment> appointments;
        boolean includeClinicalDetails;
        if (actor.isSystemAdmin()) {
            appointments = appointmentRepository
                    .findLastCompletedAppointmentByPatientAndClinic(
                            patientId,
                            clinicId,
                            currentDate);
            includeClinicalDetails = true;
        } else if (actor.isPatient()) {
            requirePatientAndClinicScope(patientId, clinicId, actor);
            appointments = appointmentRepository
                    .findLastCompletedAppointmentByPatientAndClinic(
                            patientId,
                            clinicId,
                            currentDate);
            includeClinicalDetails = true;
        } else if (actor.isDentist()) {
            requireClinicScope(clinicId, actor);
            appointments = appointmentRepository
                    .findLastCompletedAppointmentByPatientClinicAndDentist(
                            patientId,
                            clinicId,
                            actor.userId(),
                            currentDate);
            includeClinicalDetails = true;
        } else if (actor.isClinicStaff()) {
            requireClinicScope(clinicId, actor);
            appointments = appointmentRepository
                    .findLastCompletedAppointmentByPatientAndClinic(
                            patientId,
                            clinicId,
                            currentDate);
            includeClinicalDetails = false;
        } else {
            throw new AccessDeniedException("Appointment access is denied");
        }
        return mapResponses(appointments, includeClinicalDetails);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getNextUpcomingAppointmentByPatientAndClinic(
            Long patientId,
            Long clinicId,
            LocalDate currentDate,
            LocalTime currentTime,
            AppointmentActor actor) {
        List<Appointment> appointments;
        boolean includeClinicalDetails;
        if (actor.isSystemAdmin()) {
            appointments = appointmentRepository
                    .findNextUpcomingAppointmentByPatientAndClinic(
                            patientId,
                            clinicId,
                            currentDate,
                            currentTime);
            includeClinicalDetails = true;
        } else if (actor.isPatient()) {
            requirePatientAndClinicScope(patientId, clinicId, actor);
            appointments = appointmentRepository
                    .findNextUpcomingAppointmentByPatientAndClinic(
                            patientId,
                            clinicId,
                            currentDate,
                            currentTime);
            includeClinicalDetails = true;
        } else if (actor.isDentist()) {
            requireClinicScope(clinicId, actor);
            appointments = appointmentRepository
                    .findNextUpcomingAppointmentByPatientClinicAndDentist(
                            patientId,
                            clinicId,
                            actor.userId(),
                            currentDate,
                            currentTime);
            includeClinicalDetails = true;
        } else if (actor.isClinicStaff()) {
            requireClinicScope(clinicId, actor);
            appointments = appointmentRepository
                    .findNextUpcomingAppointmentByPatientAndClinic(
                            patientId,
                            clinicId,
                            currentDate,
                            currentTime);
            includeClinicalDetails = false;
        } else {
            throw new AccessDeniedException("Appointment access is denied");
        }
        return mapResponses(appointments, includeClinicalDetails);
    }

    @Transactional(readOnly = true)
    public List<Long> getDistinctPatientIdsByClinicId(
            Long clinicId,
            AppointmentActor actor) {
        if (!actor.isSystemAdmin()) {
            requireClinicScope(clinicId, actor);
        }
        return appointmentRepository.findDistinctPatientIdsByClinicId(clinicId);
    }

    private long resolveCreatePatient(
            AppointmentRequest request,
            AppointmentActor actor) {
        if (actor.isSystemAdmin()) {
            if (request.getPatientId() == null || request.getPatientId() <= 0) {
                throw new InvalidAppointmentRequestException(
                        "Patient ID is required");
            }
            return request.getPatientId();
        }
        if (!actor.isPatient()) {
            throw new AccessDeniedException("Appointment creation is denied");
        }
        if (request.getPatientId() != null
                && request.getPatientId() != actor.userId()) {
            throw new AccessDeniedException("Appointment creation is denied");
        }
        return actor.userId();
    }

    private Appointment findAuthorizedAppointment(
            Long appointmentId,
            AppointmentActor actor,
            boolean allowPatient,
            boolean allowDentist,
            boolean allowClinicStaff) {
        if (actor.isSystemAdmin()) {
            return appointmentRepository.findById(appointmentId)
                    .orElseThrow(AppointmentNotFoundException::new);
        }
        boolean hasAllowedRole = false;
        if (allowPatient && actor.isPatient()) {
            hasAllowedRole = true;
            Appointment appointment = appointmentRepository
                    .findByIdAndPatientId(appointmentId, actor.userId())
                    .orElse(null);
            if (appointment != null) {
                return appointment;
            }
        }
        if (allowDentist && actor.isDentist()) {
            hasAllowedRole = true;
            Appointment appointment = appointmentRepository
                    .findByIdAndDentistId(appointmentId, actor.userId())
                    .orElse(null);
            if (appointment != null) {
                return appointment;
            }
        }
        if (allowClinicStaff && actor.isClinicStaff()) {
            hasAllowedRole = true;
            Appointment appointment = appointmentRepository
                    .findByIdAndClinicId(appointmentId, actor.requiredClinicId())
                    .orElse(null);
            if (appointment != null) {
                return appointment;
            }
        }
        if (hasAllowedRole) {
            throw new AppointmentNotFoundException();
        }
        throw new AccessDeniedException("Appointment access is denied");
    }

    private void requireDentistAvailability(
            Long dentistId,
            Long clinicId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime) {
        boolean covered = availabilityRepository.findAvailableSlots(
                        dentistId,
                        clinicId,
                        date)
                .stream()
                .anyMatch(availability -> !startTime.isBefore(availability.getStartTime())
                        && !endTime.isAfter(availability.getEndTime()));
        if (!covered) {
            throw new InvalidAppointmentRequestException(
                    "The dentist is unavailable for the requested time");
        }
    }

    private ServiceResponse validateService(Integer serviceId, Long clinicId) {
        if (serviceId == null) {
            return null;
        }
        ApiResponse<ServiceResponse> response;
        try {
            response = clinicServiceClient.getService(serviceId);
        } catch (FeignException.NotFound exception) {
            throw new InvalidAppointmentRequestException(
                    "The selected service is unavailable at this clinic");
        } catch (Exception exception) {
            throw new AppointmentDependencyUnavailableException(exception);
        }
        if (response == null
                || !response.isSuccess()
                || response.getDataObject() == null
                || response.getDataObject().getClinicId() == null
                || response.getDataObject().getIsActive() == null) {
            throw new AppointmentDependencyUnavailableException();
        }
        ServiceResponse service = response.getDataObject();
        if (!service.getClinicId().equals(clinicId)
                || !Boolean.TRUE.equals(service.getIsActive())) {
            throw new InvalidAppointmentRequestException(
                    "The selected service is unavailable at this clinic");
        }
        requireValidSlotDuration(service.getDurationMinutes());
        return service;
    }

    private int requireValidSlotDuration(Integer durationMinutes) {
        if (durationMinutes == null
                || durationMinutes <= 0
                || durationMinutes > MAX_SLOT_DURATION_MINUTES) {
            throw new InvalidAppointmentRequestException(
                    "Service duration is invalid");
        }
        return durationMinutes;
    }

    private int requireServicelessAppointmentDuration(Integer durationMinutes) {
        int validatedDuration = requireValidSlotDuration(durationMinutes);
        if (validatedDuration != DEFAULT_SERVICELESS_APPOINTMENT_DURATION_MINUTES) {
            throw new InvalidAppointmentRequestException(
                    "Appointments without a selected service must be 30 minutes");
        }
        return validatedDuration;
    }

    private void requireServiceDuration(
            ServiceResponse service,
            LocalTime startTime,
            LocalTime endTime) {
        int expectedDurationMinutes = service == null
                ? DEFAULT_SERVICELESS_APPOINTMENT_DURATION_MINUTES
                : requireValidSlotDuration(service.getDurationMinutes());
        Duration requestedDuration = Duration.between(startTime, endTime);
        if (!requestedDuration.equals(Duration.ofMinutes(expectedDurationMinutes))) {
            throw new InvalidAppointmentRequestException(service == null
                    ? "Appointments without a selected service must be 30 minutes"
                    : "Appointment duration must match the selected service");
        }
    }

    private void requireFutureStart(LocalDate date, LocalTime startTime) {
        if (!LocalDateTime.of(date, startTime).isAfter(LocalDateTime.now())) {
            throw new InvalidAppointmentRequestException(
                    "The appointment must be scheduled in the future");
        }
    }

    private void validateTimeRange(
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime) {
        if (date == null || startTime == null || endTime == null
                || !startTime.isBefore(endTime)) {
            throw new InvalidAppointmentRequestException(
                    "Appointment time range is invalid");
        }
    }

    private Appointment runTransition(
            Supplier<Appointment> transition,
            String conflictMessage) {
        try {
            Appointment appointment = transition.get();
            if (appointment == null) {
                throw new AppointmentConflictException(conflictMessage);
            }
            return appointment;
        } catch (EmptyResultDataAccessException exception) {
            throw new AppointmentConflictException(conflictMessage);
        }
    }

    private void requirePatientAndClinicScope(
            Long patientId,
            Long clinicId,
            AppointmentActor actor) {
        if (actor.userId() != patientId) {
            throw new AppointmentNotFoundException();
        }
        if (actor.clinicId() != null && actor.clinicId() != clinicId) {
            throw new AppointmentNotFoundException();
        }
    }

    private void requireClinicScope(Long clinicId, AppointmentActor actor) {
        if (actor.requiredClinicId() != clinicId) {
            throw new AppointmentNotFoundException();
        }
    }

    private boolean mayViewClinicalDetails(
            Appointment appointment,
            AppointmentActor actor) {
        return actor.isSystemAdmin()
                || (actor.isPatient() && actor.userId() == appointment.getPatientId())
                || (actor.isDentist() && actor.userId() == appointment.getDentistId());
    }

    private List<AppointmentResponse> mapResponses(
            List<Appointment> appointments,
            boolean includeClinicalDetails) {
        return appointments.stream()
                .map(appointment -> toResponse(
                        appointment,
                        includeClinicalDetails))
                .toList();
    }

    private AppointmentResponse toResponse(
            Appointment appointment,
            boolean includeClinicalDetails) {
        AppointmentResponse.AppointmentResponseBuilder responseBuilder =
                AppointmentResponse.builder()
                        .id(appointment.getId())
                        .patientId(appointment.getPatientId())
                        .dentistId(appointment.getDentistId())
                        .clinicId(appointment.getClinicId())
                        .serviceId(appointment.getServiceId())
                        .appointmentDate(appointment.getAppointmentDate())
                        .startTime(appointment.getStartTime())
                        .endTime(appointment.getEndTime())
                        .status(appointment.getStatus().name())
                        .createdAt(appointment.getCreatedAt())
                        .updatedAt(appointment.getUpdatedAt());
        if (includeClinicalDetails) {
            responseBuilder.reasonForVisit(appointment.getReasonForVisit())
                    .symptoms(appointment.getSymptoms())
                    .urgencyLevel(appointment.getUrgency().name())
                    .aiTriageNotes(appointment.getAiTriageNotes())
                    .notes(appointment.getNotes())
                    .createdBy(appointment.getCreatedBy())
                    .confirmedBy(appointment.getConfirmedBy())
                    .cancelledBy(appointment.getCancelledBy())
                    .cancellationReason(appointment.getCancellationReason());
        }
        AppointmentResponse response = responseBuilder.build();
        enrichDisplayNames(appointment, response);
        return response;
    }

    private void enrichDisplayNames(
            Appointment appointment,
            AppointmentResponse response) {
        try {
            response.setPatientName(userProfileServiceClient.getUserFullName(
                    appointment.getPatientId()));
        } catch (Exception exception) {
            response.setPatientName("Patient " + appointment.getPatientId());
        }
        try {
            response.setDentistName(userProfileServiceClient.getUserFullName(
                    appointment.getDentistId()));
        } catch (Exception exception) {
            response.setDentistName("Dr. Dentist " + appointment.getDentistId());
        }
        try {
            var clinicResponse = clinicServiceClient.getClinic(
                    appointment.getClinicId());
            if (clinicResponse != null
                    && clinicResponse.isSuccess()
                    && clinicResponse.getDataObject() != null) {
                response.setClinicName(clinicResponse.getDataObject().getName());
            }
        } catch (Exception exception) {
            response.setClinicName("Clinic " + appointment.getClinicId());
        }
        if (appointment.getServiceId() != null) {
            try {
                var serviceResponse = clinicServiceClient.getService(
                        appointment.getServiceId());
                if (serviceResponse != null
                        && serviceResponse.isSuccess()
                        && serviceResponse.getDataObject() != null) {
                    response.setServiceName(
                            serviceResponse.getDataObject().getName());
                }
            } catch (Exception exception) {
                response.setServiceName("Service " + appointment.getServiceId());
            }
        }
    }

    private UrgencyLevel parseUrgencyLevel(String level) {
        if (level == null) {
            return UrgencyLevel.ROUTINE;
        }
        try {
            return UrgencyLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new InvalidAppointmentRequestException(
                    "Urgency level is invalid");
        }
    }

    private boolean doesTimeOverlap(
            LocalTime start1,
            LocalTime end1,
            LocalTime start2,
            LocalTime end2) {
        return start1.isBefore(end2) && end1.isAfter(start2);
    }

    private void sendCancellationNotification(
            Appointment appointment,
            String reason) {
        Map<String, Object> notificationRequest = new HashMap<>();
        notificationRequest.put("userId", appointment.getPatientId());
        notificationRequest.put("templateName", "appointment_cancelled");
        notificationRequest.put("type", "EMAIL");
        Map<String, String> templateVariables = new HashMap<>();
        templateVariables.put("patient_name", "Patient");
        templateVariables.put(
                "appointment_date",
                appointment.getAppointmentDate().toString());
        templateVariables.put("cancellation_reason", reason);
        notificationRequest.put("templateVariables", templateVariables);
        notificationClient.sendNotification(notificationRequest);
    }

    private void sendAppointmentNotification(
            Appointment appointment,
            String templateName) {
        Map<String, Object> notificationRequest = new HashMap<>();
        notificationRequest.put("userId", appointment.getPatientId());
        notificationRequest.put("templateName", templateName);
        notificationRequest.put("type", "EMAIL");
        Map<String, String> templateVariables = new HashMap<>();
        try {
            templateVariables.put(
                    "patient_name",
                    userProfileServiceClient.getUserFullName(appointment.getPatientId()));
        } catch (Exception exception) {
            templateVariables.put("patient_name", "Patient");
        }
        try {
            templateVariables.put(
                    "dentist_name",
                    userProfileServiceClient.getUserFullName(appointment.getDentistId()));
        } catch (Exception exception) {
            templateVariables.put("dentist_name", "Dr. Dentist");
        }
        templateVariables.put(
                "appointment_date",
                appointment.getAppointmentDate().toString());
        templateVariables.put(
                "appointment_time",
                appointment.getStartTime().toString());
        notificationRequest.put("templateVariables", templateVariables);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("appointment_id", appointment.getId());
        notificationRequest.put("metadata", metadata);
        notificationClient.sendNotification(notificationRequest);
    }
}
