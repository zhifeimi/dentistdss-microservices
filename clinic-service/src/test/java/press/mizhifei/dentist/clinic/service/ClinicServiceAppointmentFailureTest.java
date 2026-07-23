package press.mizhifei.dentist.clinic.service;

import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import press.mizhifei.dentist.clinic.client.AppointmentServiceClient;
import press.mizhifei.dentist.clinic.client.PatientServiceClient;
import press.mizhifei.dentist.clinic.client.UserProfileServiceClient;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClinicServiceAppointmentFailureTest {

    private ClinicRepository clinicRepository;
    private AppointmentServiceClient appointmentServiceClient;
    private ClinicService service;

    @BeforeEach
    void setUp() {
        clinicRepository = mock(ClinicRepository.class);
        appointmentServiceClient = mock(AppointmentServiceClient.class);
        service = new ClinicService(
                clinicRepository,
                appointmentServiceClient,
                mock(PatientServiceClient.class),
                mock(UserProfileServiceClient.class));
        when(clinicRepository.findById(42L))
                .thenReturn(Optional.of(new Clinic()));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 503})
    void propagatesAppointmentAuthenticationAndStateFailures(int status) {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(status);
        when(appointmentServiceClient.getClinicPatientIds(42L))
                .thenThrow(exception);

        FeignException thrown = assertThrows(
                FeignException.class,
                () -> service.getClinicPatientsSortedByAppointments(42L));

        assertSame(exception, thrown);
    }

    @Test
    void retainsFallbackForUnrelatedAppointmentFailure() {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(500);
        when(exception.getMessage()).thenReturn("downstream failure");
        when(appointmentServiceClient.getClinicPatientIds(42L))
                .thenThrow(exception);

        assertEquals(
                0,
                service.getClinicPatientsSortedByAppointments(42L).size());
    }
}
