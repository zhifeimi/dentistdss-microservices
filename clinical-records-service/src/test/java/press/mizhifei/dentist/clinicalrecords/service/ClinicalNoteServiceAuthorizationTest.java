package press.mizhifei.dentist.clinicalrecords.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import press.mizhifei.dentist.clinicalrecords.client.AuthServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.ClinicServiceClient;
import press.mizhifei.dentist.clinicalrecords.dto.ClinicalNoteResponse;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalResourceNotFoundException;
import press.mizhifei.dentist.clinicalrecords.model.ClinicalNote;
import press.mizhifei.dentist.clinicalrecords.repository.ClinicalNoteRepository;
import press.mizhifei.dentist.clinicalrecords.repository.ServiceVisitRepository;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClinicalNoteServiceAuthorizationTest {

    private ClinicalNoteRepository clinicalNoteRepository;
    private ServiceVisitRepository serviceVisitRepository;
    private ClinicalNoteService service;

    @BeforeEach
    void setUp() {
        clinicalNoteRepository = mock(ClinicalNoteRepository.class);
        serviceVisitRepository = mock(ServiceVisitRepository.class);
        service = new ClinicalNoteService(
                clinicalNoteRepository,
                serviceVisitRepository,
                mock(AuthServiceClient.class),
                mock(ClinicServiceClient.class));
    }

    @Test
    void patientReadUsesSignedOnlyPatientScope() {
        ClinicalNote note = ClinicalNote.builder()
                .id(100L)
                .patientId(42L)
                .dentistId(84L)
                .clinicId(7L)
                .isDraft(false)
                .build();
        when(clinicalNoteRepository.findByIdAndPatientIdAndIsDraftFalse(100L, 42L))
                .thenReturn(Optional.of(note));

        ClinicalNoteResponse response = service.getClinicalNote(patientActor(), 100L);

        assertEquals(100L, response.getId());
        verify(clinicalNoteRepository).findByIdAndPatientIdAndIsDraftFalse(100L, 42L);
        verify(clinicalNoteRepository, never()).findById(anyLong());
    }

    @Test
    void patientCannotReadDraftThroughUnscopedLookup() {
        when(clinicalNoteRepository.findByIdAndPatientIdAndIsDraftFalse(100L, 42L))
                .thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.getClinicalNote(patientActor(), 100L));

        verify(clinicalNoteRepository).findByIdAndPatientIdAndIsDraftFalse(100L, 42L);
        verify(clinicalNoteRepository, never()).findById(anyLong());
    }

    @Test
    void dentistCannotSignAnotherDentistsClinicalNote() {
        ClinicalRecordsActor otherDentist = new ClinicalRecordsActor(85L, Set.of("DENTIST"), 7L);
        when(clinicalNoteRepository.findByIdAndDentistIdAndClinicId(100L, 85L, 7L))
                .thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.signClinicalNote(otherDentist, 100L));

        verify(clinicalNoteRepository).findByIdAndDentistIdAndClinicId(100L, 85L, 7L);
        verify(clinicalNoteRepository, never()).save(any(ClinicalNote.class));
        verify(clinicalNoteRepository, never()).findById(anyLong());
    }

    @Test
    void systemAdminCannotSignClinicalNoteAsADentist() {
        ClinicalRecordsActor admin = new ClinicalRecordsActor(1L, Set.of("SYSTEM_ADMIN"), null);

        assertThrows(
                AccessDeniedException.class,
                () -> service.signClinicalNote(admin, 100L));

        verifyNoInteractions(clinicalNoteRepository, serviceVisitRepository);
    }

    @Test
    void dentistWithoutClinicClaimCannotSignClinicalNote() {
        ClinicalRecordsActor dentistWithoutClinic = new ClinicalRecordsActor(84L, Set.of("DENTIST"), null);

        assertThrows(
                AccessDeniedException.class,
                () -> service.signClinicalNote(dentistWithoutClinic, 100L));

        verifyNoInteractions(clinicalNoteRepository, serviceVisitRepository);
    }

    private ClinicalRecordsActor patientActor() {
        return new ClinicalRecordsActor(42L, Set.of("PATIENT"), null);
    }
}
