package press.mizhifei.dentist.clinicalrecords.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import press.mizhifei.dentist.clinicalrecords.client.AuthServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.ClinicServiceClient;
import press.mizhifei.dentist.clinicalrecords.dto.ServiceVisitRequest;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalResourceNotFoundException;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;
import press.mizhifei.dentist.clinicalrecords.repository.ServiceVisitRepository;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ServiceVisitServiceAuthorizationTest {

    private ServiceVisitRepository serviceVisitRepository;
    private ServiceVisitService service;

    @BeforeEach
    void setUp() {
        serviceVisitRepository = mock(ServiceVisitRepository.class);
        service = new ServiceVisitService(
                serviceVisitRepository,
                mock(AuthServiceClient.class),
                mock(ClinicServiceClient.class));
    }

    @Test
    void patientCannotReadAnotherPatientsServiceVisit() {
        when(serviceVisitRepository.findByIdAndPatientId(100L, 42L)).thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.getServiceVisit(patientActor(), 100L));

        verify(serviceVisitRepository).findByIdAndPatientId(100L, 42L);
        verify(serviceVisitRepository, never()).findById(anyLong());
    }

    @Test
    void dentistCannotModifyVisitOutsideTheirClinicScope() {
        when(serviceVisitRepository.findByIdAndDentistIdAndClinicId(100L, 84L, 7L))
                .thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.updateVisitNotes(dentistActor(), 100L, "Updated notes"));

        verify(serviceVisitRepository).findByIdAndDentistIdAndClinicId(100L, 84L, 7L);
        verify(serviceVisitRepository, never()).findById(anyLong());
    }

    @Test
    void dentistCannotSupplyConflictingOwnershipOnNewVisit() {
        ServiceVisitRequest request = ServiceVisitRequest.builder()
                .patientId(42L)
                .dentistId(999L)
                .clinicId(7L)
                .visitType("CONSULTATION")
                .visitDate(LocalDateTime.now())
                .build();

        assertThrows(
                InvalidClinicalRequestException.class,
                () -> service.createServiceVisit(dentistActor(), request));

        verifyNoInteractions(serviceVisitRepository);
    }

    @Test
    void clinicWideVisitQueriesRequireSystemAdmin() {
        assertThrows(
                AccessDeniedException.class,
                () -> service.getClinicVisits(dentistActor(), 7L));

        verifyNoInteractions(serviceVisitRepository);
    }

    @Test
    void dentistWithoutClinicClaimFailsClosedBeforeVisitLookup() {
        ClinicalRecordsActor dentistWithoutClinic = new ClinicalRecordsActor(84L, Set.of("DENTIST"), null);

        assertThrows(
                AccessDeniedException.class,
                () -> service.updateVisitNotes(dentistWithoutClinic, 100L, "Updated notes"));

        verifyNoInteractions(serviceVisitRepository);
    }

    private ClinicalRecordsActor patientActor() {
        return new ClinicalRecordsActor(42L, Set.of("PATIENT"), null);
    }

    private ClinicalRecordsActor dentistActor() {
        return new ClinicalRecordsActor(84L, Set.of("DENTIST"), 7L);
    }
}
