package press.mizhifei.dentist.clinic.controller;

import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import press.mizhifei.dentist.clinic.client.AuthServiceClient;
import press.mizhifei.dentist.clinic.service.ClinicService;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClinicControllerRouteTest {

    private ClinicService clinicService;
    private AuthServiceClient authServiceClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clinicService = mock(ClinicService.class);
        authServiceClient = mock(AuthServiceClient.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ClinicController(clinicService, authServiceClient))
                .build();
    }

    @Test
    void clinicApprovalMutationRouteIsNotExposed() throws Exception {
        mockMvc.perform(patch("/clinic/{id}/approve", 42L))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertNull(result.getHandler()));

        verifyNoInteractions(clinicService, authServiceClient);
    }

    @ParameterizedTest
    @CsvSource({
            "401,401,Authentication required",
            "403,403,Access denied",
            "503,503,Appointment service is temporarily unavailable",
            "500,502,Appointment service request failed"
    })
    void preservesAppointmentServiceFailureStatus(
            int downstreamStatus,
            int expectedStatus,
            String expectedMessage) throws Exception {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(downstreamStatus);
        when(clinicService.getClinicPatientsSortedByAppointments(42L))
                .thenThrow(exception);

        mockMvc.perform(get("/clinic/{id}/patients", 42L)
                        .header("X-User-Email", "staff@example.test")
                        .header("X-User-Roles", "RECEPTIONIST")
                        .header("X-Clinic-ID", "42"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(expectedMessage));

        verify(clinicService).getClinicPatientsSortedByAppointments(42L);
    }
}
