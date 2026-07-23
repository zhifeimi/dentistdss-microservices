package press.mizhifei.dentist.clinic.controller;

import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import press.mizhifei.dentist.clinic.config.ClinicSecurityConfig;
import press.mizhifei.dentist.clinic.dto.ClinicResponse;
import press.mizhifei.dentist.clinic.service.ClinicService;
import press.mizhifei.dentist.security.RedisAccessTokenJwtDecoder;
import press.mizhifei.dentist.security.ServletBearerTokenFailureHandler;
import press.mizhifei.dentist.security.ServletJwtResourceServerCustomizer;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        ClinicControllerSecurityTest.TestConfig.class,
        ClinicSecurityConfig.class
})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class ClinicControllerSecurityTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ClinicService clinicService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(clinicService);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void forgedIdentityHeadersCannotAuthenticateClinicRoute() throws Exception {
        mockMvc.perform(get("/clinic/{clinicId}/patients", 7L)
                        .header("X-User-ID", "42")
                        .header("X-User-Roles", "SYSTEM_ADMIN")
                        .header("X-Clinic-ID", "7"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(clinicService);
    }

    @Test
    void publicDirectoryStaysAnonymous() throws Exception {
        when(clinicService.listAllEnabledClinics()).thenReturn(List.of());

        mockMvc.perform(get("/clinic/list/all"))
                .andExpect(status().isOk());

        verify(clinicService).listAllEnabledClinics();
    }

    @Test
    void clinicLookupRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/clinic/{id}", 7L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(clinicService);
    }

    @Test
    void patientCannotUpdateClinic() throws Exception {
        mockMvc.perform(put("/clinic/{id}", 7L)
                        .with(patientJwt())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clinicService);
    }

    @Test
    void clinicAdminCannotUpdateAnotherClinic() throws Exception {
        mockMvc.perform(put("/clinic/{id}", 8L)
                        .with(clinicAdminJwt(7L))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "Access denied. You can only update your own clinic."));

        verifyNoInteractions(clinicService);
    }

    @Test
    void clinicAdminUpdatesOwnClinic() throws Exception {
        ClinicResponse updated = ClinicResponse.builder().id(7L).build();
        when(clinicService.updateClinic(eq(7L), any())).thenReturn(updated);

        mockMvc.perform(put("/clinic/{id}", 7L)
                        .with(clinicAdminJwt(7L))
                        .contentType("application/json")
                        .content("{\"name\":\"Own Dental\"}"))
                .andExpect(status().isOk());

        verify(clinicService).updateClinic(eq(7L), any());
    }

    @Test
    void systemAdminUpdatesAnyClinic() throws Exception {
        ClinicResponse updated = ClinicResponse.builder().id(8L).build();
        when(clinicService.updateClinic(eq(8L), any())).thenReturn(updated);

        mockMvc.perform(put("/clinic/{id}", 8L)
                        .with(systemAdminJwt())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());

        verify(clinicService).updateClinic(eq(8L), any());
    }

    @Test
    void patientCannotListClinicPatients() throws Exception {
        mockMvc.perform(get("/clinic/{clinicId}/patients", 7L)
                        .with(patientJwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clinicService);
    }

    @Test
    void receptionistSeesOwnClinicPatients() throws Exception {
        when(clinicService.getClinicPatientsSortedByAppointments(7L)).thenReturn(List.of());

        mockMvc.perform(get("/clinic/{clinicId}/patients", 7L)
                        .with(receptionistJwt(7L)))
                .andExpect(status().isOk());

        verify(clinicService).getClinicPatientsSortedByAppointments(7L);
    }

    @Test
    void receptionistCannotListAnotherClinicsPatients() throws Exception {
        mockMvc.perform(get("/clinic/{clinicId}/patients", 8L)
                        .with(receptionistJwt(7L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "Access denied. You can only view patients from your own clinic."));

        verifyNoInteractions(clinicService);
    }

    @Test
    void appointmentServiceFailureStatusIsPreserved() throws Exception {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(503);
        when(clinicService.getClinicPatientsSortedByAppointments(7L)).thenThrow(exception);

        mockMvc.perform(get("/clinic/{clinicId}/patients", 7L)
                        .with(receptionistJwt(7L)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(
                        "Appointment service is temporarily unavailable"));
    }

    @Test
    void clinicDentistsAllowAnyAuthenticatedUser() throws Exception {
        when(clinicService.getClinicDentists(7L)).thenReturn(List.of());

        mockMvc.perform(get("/clinic/{clinicId}/dentists", 7L)
                        .with(patientJwt()))
                .andExpect(status().isOk());

        verify(clinicService).getClinicDentists(7L);

        mockMvc.perform(get("/clinic/{clinicId}/dentists", 7L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clinicApprovalMutationRouteIsNotExposed() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/clinic/{id}/approve", 42L)
                        .with(systemAdminJwt()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(clinicService);
    }

    @Test
    void authenticatedUnknownRouteIsDenied() throws Exception {
        mockMvc.perform(get("/not-a-clinic-route").with(clinicAdminJwt(7L)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clinicService);
    }

    private RequestPostProcessor patientJwt() {
        return jwt()
                .jwt(token -> token
                        .subject("42")
                        .claim("roles", List.of("PATIENT")))
                .authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
    }

    private RequestPostProcessor receptionistJwt(Long clinicId) {
        return jwt()
                .jwt(token -> token
                        .subject("66")
                        .claim("roles", List.of("RECEPTIONIST"))
                        .claim("clinicId", clinicId))
                .authorities(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"));
    }

    private RequestPostProcessor clinicAdminJwt(Long clinicId) {
        return jwt()
                .jwt(token -> token
                        .subject("55")
                        .claim("roles", List.of("CLINIC_ADMIN"))
                        .claim("clinicId", clinicId))
                .authorities(new SimpleGrantedAuthority("ROLE_CLINIC_ADMIN"));
    }

    private RequestPostProcessor systemAdminJwt() {
        return jwt()
                .jwt(token -> token
                        .subject("1")
                        .claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        ClinicService clinicService() {
            return mock(ClinicService.class);
        }

        @Bean
        ClinicController clinicController(ClinicService clinicService) {
            return new ClinicController(clinicService);
        }

        @Bean
        ServletJwtResourceServerCustomizer servletJwtResourceServerCustomizer() {
            return new ServletJwtResourceServerCustomizer(
                    mock(RedisAccessTokenJwtDecoder.class),
                    new JwtAuthenticationConverter(),
                    new ServletBearerTokenFailureHandler());
        }
    }
}
