package press.mizhifei.dentist.clinicalrecords.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import press.mizhifei.dentist.clinicalrecords.client.AuthServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.ClinicServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.NotificationClient;
import press.mizhifei.dentist.clinicalrecords.dto.TreatmentPlanRequest;
import press.mizhifei.dentist.clinicalrecords.dto.TreatmentPlanResponse;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalResourceNotFoundException;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;
import press.mizhifei.dentist.clinicalrecords.model.TreatmentPlan;
import press.mizhifei.dentist.clinicalrecords.repository.TreatmentPlanItemRepository;
import press.mizhifei.dentist.clinicalrecords.repository.TreatmentPlanRepository;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TreatmentPlanServiceAuthorizationTest {

    private TreatmentPlanRepository treatmentPlanRepository;
    private TreatmentPlanItemRepository treatmentPlanItemRepository;
    private AuthServiceClient authServiceClient;
    private ClinicServiceClient clinicServiceClient;
    private NotificationClient notificationClient;
    private TreatmentPlanService service;

    @BeforeEach
    void setUp() {
        treatmentPlanRepository = mock(TreatmentPlanRepository.class);
        treatmentPlanItemRepository = mock(TreatmentPlanItemRepository.class);
        authServiceClient = mock(AuthServiceClient.class);
        clinicServiceClient = mock(ClinicServiceClient.class);
        notificationClient = mock(NotificationClient.class);
        service = new TreatmentPlanService(
                treatmentPlanRepository,
                treatmentPlanItemRepository,
                authServiceClient,
                clinicServiceClient,
                notificationClient);
    }

    @Test
    void patientAcceptanceUsesOnlyTheirScopedPlanLookup() {
        TreatmentPlan plan = treatmentPlan(100, 42L, 84L, 7L, "PROPOSED");
        when(treatmentPlanRepository.findByIdAndPatientId(100, 42L)).thenReturn(Optional.of(plan));
        when(treatmentPlanRepository.save(plan)).thenReturn(plan);
        when(treatmentPlanItemRepository.findByTreatmentPlanIdOrderBySequenceOrder(100))
                .thenReturn(List.of());

        TreatmentPlanResponse response = service.acceptTreatmentPlan(patientActor(), 100);

        assertEquals("ACCEPTED", response.getStatus());
        verify(treatmentPlanRepository).findByIdAndPatientId(100, 42L);
        verify(treatmentPlanRepository, never()).findById(anyInt());
    }

    @Test
    void patientCannotAcceptAnotherPatientsPlan() {
        when(treatmentPlanRepository.findByIdAndPatientId(100, 42L)).thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.acceptTreatmentPlan(patientActor(), 100));

        verify(treatmentPlanRepository).findByIdAndPatientId(100, 42L);
        verify(treatmentPlanRepository, never()).findById(anyInt());
        verifyNoInteractions(treatmentPlanItemRepository, notificationClient);
    }

    @Test
    void dentistWithoutClinicClaimCannotManagePlan() {
        ClinicalRecordsActor actor = new ClinicalRecordsActor(84L, Set.of("DENTIST"), null);

        assertThrows(
                AccessDeniedException.class,
                () -> service.updateTreatmentPlanItemStatus(actor, 100, 1, "COMPLETED"));

        verifyNoInteractions(treatmentPlanRepository, treatmentPlanItemRepository);
    }

    @Test
    void dentistCannotChangeItemUntilParentPlanIsInTheirClinicScope() {
        when(treatmentPlanRepository.findByIdAndDentistIdAndClinicId(100, 84L, 7L))
                .thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.updateTreatmentPlanItemStatus(dentistActor(), 100, 1, "COMPLETED"));

        verify(treatmentPlanRepository).findByIdAndDentistIdAndClinicId(100, 84L, 7L);
        verify(treatmentPlanRepository, never()).findById(anyInt());
        verifyNoInteractions(treatmentPlanItemRepository);
    }

    @Test
    void planItemLookupIsBoundToTheAuthorizedParentPlan() {
        TreatmentPlan plan = treatmentPlan(100, 42L, 84L, 7L, "IN_PROGRESS");
        when(treatmentPlanRepository.findByIdAndDentistIdAndClinicId(100, 84L, 7L))
                .thenReturn(Optional.of(plan));
        when(treatmentPlanItemRepository.findByIdAndTreatmentPlanId(5, 100))
                .thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.updateTreatmentPlanItemStatus(dentistActor(), 100, 5, "COMPLETED"));

        verify(treatmentPlanItemRepository).findByIdAndTreatmentPlanId(5, 100);
        verify(treatmentPlanItemRepository, never()).findById(anyInt());
    }

    @Test
    void dentistCannotOverrideActorDerivedOwnershipWhenCreatingPlan() {
        TreatmentPlanRequest request = TreatmentPlanRequest.builder()
                .patientId(42L)
                .dentistId(999L)
                .clinicId(7L)
                .build();

        assertThrows(
                InvalidClinicalRequestException.class,
                () -> service.createTreatmentPlan(dentistActor(), request));

        verifyNoInteractions(
                treatmentPlanRepository,
                treatmentPlanItemRepository,
                authServiceClient,
                clinicServiceClient,
                notificationClient);
    }

    @Test
    void systemAdminUsesExplicitGlobalManagementOverride() {
        TreatmentPlan plan = treatmentPlan(100, 42L, 84L, 7L, "ACCEPTED");
        ClinicalRecordsActor admin = new ClinicalRecordsActor(1L, Set.of("SYSTEM_ADMIN"), null);
        when(treatmentPlanRepository.findById(100)).thenReturn(Optional.of(plan));
        when(treatmentPlanRepository.save(plan)).thenReturn(plan);
        when(treatmentPlanItemRepository.findByTreatmentPlanIdOrderBySequenceOrder(100))
                .thenReturn(List.of());

        TreatmentPlanResponse response = service.startTreatmentPlan(admin, 100);

        assertEquals("IN_PROGRESS", response.getStatus());
        verify(treatmentPlanRepository).findById(100);
        verify(treatmentPlanRepository, never()).findByIdAndDentistIdAndClinicId(100, 84L, 7L);
    }

    private ClinicalRecordsActor patientActor() {
        return new ClinicalRecordsActor(42L, Set.of("PATIENT"), null);
    }

    private ClinicalRecordsActor dentistActor() {
        return new ClinicalRecordsActor(84L, Set.of("DENTIST"), 7L);
    }

    private TreatmentPlan treatmentPlan(
            Integer id,
            Long patientId,
            Long dentistId,
            Long clinicId,
            String status) {
        return TreatmentPlan.builder()
                .id(id)
                .patientId(patientId)
                .dentistId(dentistId)
                .clinicId(clinicId)
                .status(status)
                .build();
    }
}
