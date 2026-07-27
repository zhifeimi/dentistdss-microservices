package press.mizhifei.dentist.clinic.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import press.mizhifei.dentist.clinic.exception.GlobalExceptionHandler;
import press.mizhifei.dentist.clinic.exception.ServiceNotFoundException;
import press.mizhifei.dentist.clinic.service.ServiceManagementService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ServiceManagementControllerTest {

    private ServiceManagementService serviceManagementService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        serviceManagementService = mock(ServiceManagementService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ServiceManagementController(serviceManagementService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unknownServiceReturnsSanitizedNotFound() throws Exception {
        when(serviceManagementService.getService(42))
                .thenThrow(new ServiceNotFoundException());

        mockMvc.perform(get("/clinic/service/{id}", 42))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Service not found"));
    }
}
