package press.mizhifei.dentist.appointment.service;

import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import press.mizhifei.dentist.appointment.client.UserProfileServiceClient;
import press.mizhifei.dentist.appointment.client.ClinicServiceClient;
import press.mizhifei.dentist.appointment.client.NotificationClient;
import press.mizhifei.dentist.appointment.client.ServiceResponse;
import press.mizhifei.dentist.appointment.dto.ApiResponse;
import press.mizhifei.dentist.appointment.dto.AppointmentRequest;
import press.mizhifei.dentist.appointment.dto.AppointmentResponse;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppointmentServiceAuthorizationTest {

    private AppointmentRepository appointmentRepository;
    private DentistAvailabilityRepository availabilityRepository;
    private NotificationClient notificationClient;
    private UserProfileServiceClient userProfileServiceClient;
    private ClinicServiceClient clinicServiceClient;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        availabilityRepository = mock(DentistAvailabilityRepository.class);
        notificationClient = mock(NotificationClient.class);
        userProfileServiceClient = mock(UserProfileServiceClient.class);
        clinicServiceClient = mock(ClinicServiceClient.class);
        service = new AppointmentService(
                appointmentRepository,
                availabilityRepository,
                notificationClient,
                userProfileServiceClient,
                clinicServiceClient);
    }

    @Test
    void patientCannotCreateForAnotherPatient() {
        AppointmentRequest request = validRequest();
        request.setPatientId(41L);
        request.setCreatedBy(999L);

        assertThrows(
                AccessDeniedException.class,
                () -> service.createAppointment(
                        request,
                        patientActor()));

        verifyNoInteractions(
                appointmentRepository,
                availabilityRepository,
                notificationClient,
                userProfileServiceClient,
                clinicServiceClient);
    }

    @Test
    void patientCreationDerivesPatientAndCreatorFromActor() {
        AppointmentRequest request = validRequest();
        request.setCreatedBy(999L);
        DentistAvailability availability = DentistAvailability.builder()
                .dentistId(84L)
                .clinicId(7L)
                .availableDate(request.getAppointmentDate())
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(17, 0))
                .isBlocked(false)
                .build();
        Appointment saved = appointment(100L, 42L, 84L, 7L);

        when(availabilityRepository.findAvailableSlots(
                84L,
                7L,
                request.getAppointmentDate()))
                .thenReturn(List.of(availability));
        when(appointmentRepository.findConflictingAppointments(
                84L,
                request.getAppointmentDate(),
                request.getStartTime(),
                request.getEndTime(),
                List.of(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)))
                .thenReturn(List.of());
        when(appointmentRepository.saveWithCasting(
                eq(42L),
                eq(84L),
                eq(7L),
                isNull(),
                eq(request.getAppointmentDate()),
                eq(request.getStartTime()),
                eq(request.getEndTime()),
                eq("REQUESTED"),
                eq("Checkup"),
                eq("Sensitivity"),
                eq("MODERATE"),
                isNull(),
                eq("Notes"),
                eq(42L)))
                .thenReturn(saved);

        service.createAppointment(request, patientActor());

        InOrder appointmentWriteOrder = inOrder(appointmentRepository);
        appointmentWriteOrder.verify(appointmentRepository)
                .acquireDentistScheduleLock(84L);
        appointmentWriteOrder.verify(appointmentRepository)
                .findConflictingAppointments(
                        84L,
                        request.getAppointmentDate(),
                        request.getStartTime(),
                        request.getEndTime(),
                        List.of(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW));
        appointmentWriteOrder.verify(appointmentRepository).saveWithCasting(
                eq(42L),
                eq(84L),
                eq(7L),
                isNull(),
                eq(request.getAppointmentDate()),
                eq(request.getStartTime()),
                eq(request.getEndTime()),
                eq("REQUESTED"),
                eq("Checkup"),
                eq("Sensitivity"),
                eq("MODERATE"),
                isNull(),
                eq("Notes"),
                eq(42L));
    }

    @Test
    void patientCannotCancelAppointmentTheyDoNotOwn() {
        when(appointmentRepository.findByIdAndPatientId(100L, 42L))
                .thenReturn(Optional.empty());

        assertThrows(
                AppointmentNotFoundException.class,
                () -> service.cancelAppointment(
                        100L,
                        "Schedule changed",
                        patientActor()));

        verify(appointmentRepository, never())
                .cancelAppointment(any(), any(), any());
        verify(appointmentRepository, never()).findById(any());
    }

    @Test
    void dentistConfirmationUsesAssignedDentistScopeAndJwtActor() {
        Appointment appointment = appointment(100L, 42L, 84L, 7L);
        Appointment confirmed = appointment(100L, 42L, 84L, 7L);
        confirmed.setStatus(AppointmentStatus.CONFIRMED);
        confirmed.setConfirmedBy(84L);
        AppointmentActor actor = new AppointmentActor(
                84L,
                Set.of("DENTIST"),
                7L);

        when(appointmentRepository.findByIdAndDentistId(100L, 84L))
                .thenReturn(Optional.of(appointment));
        when(appointmentRepository.confirmAppointment(100L, 84L))
                .thenReturn(confirmed);

        service.confirmAppointment(100L, actor);

        verify(appointmentRepository).findByIdAndDentistId(100L, 84L);
        verify(appointmentRepository).confirmAppointment(100L, 84L);
        verify(appointmentRepository, never()).findById(any());
    }

    @Test
    void confirmationSendsValidNotificationPayloadForThePatient() {
        Appointment appointment = appointment(100L, 42L, 84L, 7L);
        Appointment confirmed = appointment(100L, 42L, 84L, 7L);
        confirmed.setStatus(AppointmentStatus.CONFIRMED);
        confirmed.setConfirmedBy(84L);
        AppointmentActor actor = new AppointmentActor(
                84L,
                Set.of("DENTIST"),
                7L);
        when(appointmentRepository.findByIdAndDentistId(100L, 84L))
                .thenReturn(Optional.of(appointment));
        when(appointmentRepository.confirmAppointment(100L, 84L))
                .thenReturn(confirmed);
        when(userProfileServiceClient.getUserFullName(42L)).thenReturn("Jane Patient");
        when(userProfileServiceClient.getUserFullName(84L)).thenReturn("Ann Dentist");

        service.confirmAppointment(100L, actor);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).sendNotification(captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals(42L, payload.get("userId"));
        assertEquals("EMAIL", payload.get("type"));
        assertEquals("Your appointment is confirmed", payload.get("subject"));
        String body = (String) payload.get("body");
        assertTrue(body.contains("Jane Patient"));
        assertTrue(body.contains("Ann Dentist"));
        assertTrue(body.contains(appointment.getAppointmentDate().toString()));
        assertEquals(Map.of("appointment_id", 100L), payload.get("metadata"));
    }

    @Test
    void cancellationSendsValidNotificationPayloadWithTheCancellationReason() {
        Appointment appointment = appointment(100L, 42L, 84L, 7L);
        Appointment cancelled = appointment(100L, 42L, 84L, 7L);
        cancelled.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findByIdAndPatientId(100L, 42L))
                .thenReturn(Optional.of(appointment));
        when(appointmentRepository.cancelAppointment(100L, "Schedule changed", 42L))
                .thenReturn(cancelled);

        service.cancelAppointment(100L, "Schedule changed", patientActor());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).sendNotification(captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals(42L, payload.get("userId"));
        assertEquals("EMAIL", payload.get("type"));
        assertEquals("Your appointment has been cancelled", payload.get("subject"));
        String body = (String) payload.get("body");
        assertTrue(body.contains(appointment.getAppointmentDate().toString()));
        assertTrue(body.contains("Schedule changed"));
        assertEquals(Map.of("appointment_id", 100L), payload.get("metadata"));
    }

    @Test
    void clinicStaffCannotReadAnotherClinic() {
        AppointmentActor actor = new AppointmentActor(
                90L,
                Set.of("RECEPTIONIST"),
                7L);

        assertThrows(
                AppointmentNotFoundException.class,
                () -> service.getClinicAppointments(
                        8L,
                        LocalDate.now(),
                        actor));

        verifyNoInteractions(appointmentRepository);
    }

    @Test
    void patientCannotListAnotherPatientsAppointments() {
        assertThrows(
                AppointmentNotFoundException.class,
                () -> service.getPatientAppointments(
                        41L,
                        patientActor()));

        verifyNoInteractions(appointmentRepository);
    }

    @Test
    void dentistCannotListAnotherDentistsAppointments() {
        AppointmentActor actor = new AppointmentActor(
                84L,
                Set.of("DENTIST"),
                7L);

        assertThrows(
                AppointmentNotFoundException.class,
                () -> service.getDentistAppointments(
                        85L,
                        LocalDate.now(),
                        actor));

        verifyNoInteractions(appointmentRepository);
    }

    @Test
    void clinicStaffWithoutClinicClaimFailsClosed() {
        AppointmentActor actor = new AppointmentActor(
                90L,
                Set.of("RECEPTIONIST"),
                null);

        assertThrows(
                AccessDeniedException.class,
                () -> service.getClinicAppointments(
                        7L,
                        LocalDate.now(),
                        actor));

        verifyNoInteractions(appointmentRepository);
    }

    @Test
    void creationRejectsServiceFromAnotherClinic() {
        AppointmentRequest request = validRequest();
        request.setServiceId(1);
        ServiceResponse clinicService = new ServiceResponse();
        clinicService.setClinicId(8L);
        clinicService.setIsActive(true);

        when(availabilityRepository.findAvailableSlots(
                84L,
                7L,
                request.getAppointmentDate()))
                .thenReturn(List.of(availableAllDay(request)));
        when(clinicServiceClient.getService(1))
                .thenReturn(ApiResponse.success(clinicService));

        assertThrows(
                InvalidAppointmentRequestException.class,
                () -> service.createAppointment(request, patientActor()));

        verify(appointmentRepository, never()).saveWithCasting(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void creationRejectsUnknownServiceAsInvalidInput() {
        AppointmentRequest request = validRequest();
        request.setServiceId(1);
        FeignException.NotFound notFound = mock(FeignException.NotFound.class);

        when(availabilityRepository.findAvailableSlots(
                84L,
                7L,
                request.getAppointmentDate()))
                .thenReturn(List.of(availableAllDay(request)));
        when(clinicServiceClient.getService(1)).thenThrow(notFound);

        assertThrows(
                InvalidAppointmentRequestException.class,
                () -> service.createAppointment(request, patientActor()));

        verify(appointmentRepository, never()).saveWithCasting(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void creationFailsClosedForMalformedServiceResponse() {
        AppointmentRequest request = validRequest();
        request.setServiceId(1);
        @SuppressWarnings("unchecked")
        ApiResponse<ServiceResponse> response = mock(ApiResponse.class);
        when(response.isSuccess()).thenReturn(true);

        when(availabilityRepository.findAvailableSlots(
                84L,
                7L,
                request.getAppointmentDate()))
                .thenReturn(List.of(availableAllDay(request)));
        when(clinicServiceClient.getService(1)).thenReturn(response);

        assertThrows(
                AppointmentDependencyUnavailableException.class,
                () -> service.createAppointment(request, patientActor()));

        verify(appointmentRepository, never()).saveWithCasting(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void creationRejectsSelectedServiceDurationWithExtraSeconds() {
        AppointmentRequest request = validRequest();
        request.setServiceId(1);
        request.setEndTime(LocalTime.of(10, 30, 1));
        ServiceResponse clinicService = activeService(1, 7L, 30);

        when(availabilityRepository.findAvailableSlots(
                84L,
                7L,
                request.getAppointmentDate()))
                .thenReturn(List.of(availableAllDay(request)));
        when(clinicServiceClient.getService(1))
                .thenReturn(ApiResponse.success(clinicService));

        assertThrows(
                InvalidAppointmentRequestException.class,
                () -> service.createAppointment(request, patientActor()));

        verify(appointmentRepository, never()).acquireDentistScheduleLock(any());
        verify(appointmentRepository, never()).saveWithCasting(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void creationWithoutServiceRequiresTheDefaultThirtyMinuteDuration() {
        AppointmentRequest request = validRequest();
        request.setEndTime(LocalTime.of(10, 31));

        when(availabilityRepository.findAvailableSlots(
                84L,
                7L,
                request.getAppointmentDate()))
                .thenReturn(List.of(availableAllDay(request)));

        assertThrows(
                InvalidAppointmentRequestException.class,
                () -> service.createAppointment(request, patientActor()));

        verify(appointmentRepository, never()).acquireDentistScheduleLock(any());
        verify(appointmentRepository, never()).saveWithCasting(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rescheduledAppointmentCanBecomeNoShowAfterItStarts() {
        Appointment authorized = appointment(100L, 42L, 84L, 7L);
        authorized.setStatus(AppointmentStatus.RESCHEDULED);
        authorized.setAppointmentDate(LocalDate.now().minusDays(1));
        Appointment noShow = appointment(100L, 42L, 84L, 7L);
        noShow.setStatus(AppointmentStatus.NO_SHOW);
        noShow.setAppointmentDate(authorized.getAppointmentDate());

        AppointmentActor actor = new AppointmentActor(
                84L,
                Set.of("DENTIST"),
                7L);
        when(appointmentRepository.findByIdAndDentistId(100L, 84L))
                .thenReturn(Optional.of(authorized));
        when(appointmentRepository.markNoShow(100L)).thenReturn(noShow);

        AppointmentResponse response = service.markNoShow(100L, actor);

        assertEquals("NO_SHOW", response.getStatus());
        verify(appointmentRepository).markNoShow(100L);
    }

    @Test
    void futureAppointmentCannotBecomeNoShow() {
        Appointment authorized = appointment(100L, 42L, 84L, 7L);
        authorized.setStatus(AppointmentStatus.RESCHEDULED);
        AppointmentActor actor = new AppointmentActor(
                84L,
                Set.of("DENTIST"),
                7L);
        when(appointmentRepository.findByIdAndDentistId(100L, 84L))
                .thenReturn(Optional.of(authorized));

        assertThrows(
                AppointmentConflictException.class,
                () -> service.markNoShow(100L, actor));

        verify(appointmentRepository, never()).markNoShow(any());
    }

    @Test
    void availableSlotsExcludeCancelledAndNoShowAppointmentsFromBlockingQuery() {
        AppointmentRequest request = validRequest();
        when(availabilityRepository.findAvailableSlots(
                84L,
                7L,
                request.getAppointmentDate()))
                .thenReturn(List.of(availableAllDay(request)));
        when(appointmentRepository.findSlotBlockingAppointments(
                84L,
                request.getAppointmentDate(),
                List.of(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)))
                .thenReturn(List.of());

        List<?> slots = service.getAvailableSlots(
                84L,
                7L,
                request.getAppointmentDate(),
                null,
                30,
                patientActor());

        assertEquals(35, slots.size());
        verify(appointmentRepository).findSlotBlockingAppointments(
                84L,
                request.getAppointmentDate(),
                List.of(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW));
    }

    @Test
    void failedExpectedStateTransitionReturnsConflict() {
        Appointment appointment = appointment(100L, 42L, 84L, 7L);
        AppointmentActor actor = new AppointmentActor(
                84L,
                Set.of("DENTIST"),
                7L);

        when(appointmentRepository.findByIdAndDentistId(100L, 84L))
                .thenReturn(Optional.of(appointment));
        when(appointmentRepository.confirmAppointment(100L, 84L))
                .thenReturn(null);

        assertThrows(
                AppointmentConflictException.class,
                () -> service.confirmAppointment(100L, actor));

        verifyNoInteractions(notificationClient);
    }

    @Test
    void availableSlotsRejectsUnboundedDurationBeforeRepositoryAccess() {
        assertThrows(
                InvalidAppointmentRequestException.class,
                () -> service.getAvailableSlots(
                        84L,
                        7L,
                        LocalDate.now(),
                        null,
                        481,
                        patientActor()));

        verifyNoInteractions(appointmentRepository, availabilityRepository);
    }

    @Test
    void availableSlotsWithoutServiceRejectsDurationsOtherThanThirtyMinutes() {
        assertThrows(
                InvalidAppointmentRequestException.class,
                () -> service.getAvailableSlots(
                        84L,
                        7L,
                        LocalDate.now().plusDays(1),
                        null,
                        45,
                        patientActor()));

        verifyNoInteractions(appointmentRepository, availabilityRepository);
    }

    @Test
    void availableSlotsUseThePersistedServiceDurationInsteadOfClientDuration() {
        LocalDate date = LocalDate.now().plusDays(1);
        ServiceResponse clinicService = activeService(1, 7L, 45);
        DentistAvailability availability = DentistAvailability.builder()
                .dentistId(84L)
                .clinicId(7L)
                .availableDate(date)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(17, 0))
                .isBlocked(false)
                .build();

        when(clinicServiceClient.getService(1))
                .thenReturn(ApiResponse.success(clinicService));
        when(availabilityRepository.findAvailableSlots(84L, 7L, date))
                .thenReturn(List.of(availability));
        when(appointmentRepository.findSlotBlockingAppointments(
                84L,
                date,
                List.of(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)))
                .thenReturn(List.of());

        List<?> slots = service.getAvailableSlots(
                84L,
                7L,
                date,
                1,
                30,
                patientActor());

        assertEquals(34, slots.size());
    }

    @Test
    void rescheduleAcquiresDentistScheduleLockBeforeChangingAppointment() {
        Appointment authorized = appointment(100L, 42L, 84L, 7L);
        LocalDate newDate = LocalDate.now().plusDays(2);
        LocalTime newStartTime = LocalTime.of(11, 0);
        LocalTime newEndTime = LocalTime.of(11, 30);
        DentistAvailability availability = DentistAvailability.builder()
                .dentistId(84L)
                .clinicId(7L)
                .availableDate(newDate)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(17, 0))
                .isBlocked(false)
                .build();
        Appointment rescheduled = appointment(100L, 42L, 84L, 7L);
        rescheduled.setAppointmentDate(newDate);
        rescheduled.setStartTime(newStartTime);
        rescheduled.setEndTime(newEndTime);
        rescheduled.setStatus(AppointmentStatus.RESCHEDULED);

        when(appointmentRepository.findByIdAndPatientId(100L, 42L))
                .thenReturn(Optional.of(authorized));
        when(availabilityRepository.findAvailableSlots(84L, 7L, newDate))
                .thenReturn(List.of(availability));
        when(appointmentRepository.findConflictingAppointments(
                84L,
                newDate,
                newStartTime,
                newEndTime,
                List.of(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)))
                .thenReturn(List.of());
        when(appointmentRepository.rescheduleAppointment(
                100L,
                newDate,
                newStartTime,
                newEndTime))
                .thenReturn(rescheduled);

        service.rescheduleAppointment(
                100L,
                newDate,
                newStartTime,
                newEndTime,
                patientActor());

        InOrder appointmentWriteOrder = inOrder(appointmentRepository);
        appointmentWriteOrder.verify(appointmentRepository)
                .acquireDentistScheduleLock(84L);
        appointmentWriteOrder.verify(appointmentRepository)
                .findConflictingAppointments(
                        84L,
                        newDate,
                        newStartTime,
                        newEndTime,
                        List.of(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW));
        appointmentWriteOrder.verify(appointmentRepository).rescheduleAppointment(
                100L,
                newDate,
                newStartTime,
                newEndTime);
    }

    @Test
    void clinicStaffListRedactsClinicalAndActorFields() {
        Appointment appointment = appointment(100L, 42L, 84L, 7L);
        appointment.setReasonForVisit("Sensitive reason");
        appointment.setSymptoms("Sensitive symptoms");
        appointment.setAiTriageNotes("Sensitive triage");
        appointment.setNotes("Sensitive notes");
        appointment.setCreatedBy(42L);
        appointment.setConfirmedBy(84L);
        appointment.setCancelledBy(90L);
        appointment.setCancellationReason("Sensitive cancellation reason");
        LocalDate date = LocalDate.now();
        AppointmentActor actor = new AppointmentActor(
                90L,
                Set.of("CLINIC_ADMIN"),
                7L);

        when(appointmentRepository
                .findByClinicIdAndAppointmentDateOrderByStartTime(7L, date))
                .thenReturn(List.of(appointment));

        List<AppointmentResponse> responses = service.getClinicAppointments(
                7L,
                date,
                actor);

        assertEquals(1, responses.size());
        AppointmentResponse response = responses.getFirst();
        assertEquals(100L, response.getId());
        assertEquals("REQUESTED", response.getStatus());
        assertNull(response.getReasonForVisit());
        assertNull(response.getSymptoms());
        assertNull(response.getAiTriageNotes());
        assertNull(response.getNotes());
        assertNull(response.getCreatedBy());
        assertNull(response.getConfirmedBy());
        assertNull(response.getCancelledBy());
        assertNull(response.getCancellationReason());
    }

    private AppointmentRequest validRequest() {
        return AppointmentRequest.builder()
                .dentistId(84L)
                .clinicId(7L)
                .appointmentDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(10, 30))
                .reasonForVisit("Checkup")
                .symptoms("Sensitivity")
                .urgencyLevel("MODERATE")
                .notes("Notes")
                .build();
    }

    private DentistAvailability availableAllDay(AppointmentRequest request) {
        return DentistAvailability.builder()
                .dentistId(request.getDentistId())
                .clinicId(request.getClinicId())
                .availableDate(request.getAppointmentDate())
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(17, 0))
                .isBlocked(false)
                .build();
    }

    private ServiceResponse activeService(
            Integer id,
            Long clinicId,
            Integer durationMinutes) {
        ServiceResponse service = new ServiceResponse();
        service.setId(id);
        service.setClinicId(clinicId);
        service.setDurationMinutes(durationMinutes);
        service.setIsActive(true);
        return service;
    }

    private AppointmentActor patientActor() {
        return new AppointmentActor(42L, Set.of("PATIENT"), null);
    }

    private Appointment appointment(
            Long id,
            Long patientId,
            Long dentistId,
            Long clinicId) {
        return Appointment.builder()
                .id(id)
                .patientId(patientId)
                .dentistId(dentistId)
                .clinicId(clinicId)
                .appointmentDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(10, 30))
                .status(AppointmentStatus.REQUESTED)
                .urgency(UrgencyLevel.MODERATE)
                .build();
    }
}
