package press.mizhifei.dentist.appointment.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import press.mizhifei.dentist.appointment.config.AppointmentSecurityConfig;
import press.mizhifei.dentist.appointment.dto.AppointmentResponse;
import press.mizhifei.dentist.appointment.security.AppointmentActor;
import press.mizhifei.dentist.appointment.service.AppointmentService;
import press.mizhifei.dentist.security.RedisAccessTokenJwtDecoder;
import press.mizhifei.dentist.security.ServletBearerTokenFailureHandler;
import press.mizhifei.dentist.security.ServletJwtResourceServerCustomizer;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        AppointmentControllerSecurityTest.TestConfig.class,
        AppointmentSecurityConfig.class
})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class AppointmentControllerSecurityTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private AppointmentService appointmentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(appointmentService);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void forgedIdentityHeadersCannotReplaceAuthentication() throws Exception {
        mockMvc.perform(get("/appointment/patient/{patientId}", 42L)
                        .header("X-User-ID", "42")
                        .header("X-User-Roles", "PATIENT")
                        .header("X-Clinic-ID", "7"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(appointmentService);
    }

    @Test
    void patientCannotCallDentistConfirmation() throws Exception {
        mockMvc.perform(patch("/appointment/{id}/confirm", 100L)
                        .with(patientJwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(appointmentService);
    }

    @Test
    void patientJwtControlsActorDespiteForgedHeaders() throws Exception {
        when(appointmentService.getPatientAppointments(eq(42L), argThat(
                actor -> actor.userId() == 42L
                        && actor.roles().equals(java.util.Set.of("PATIENT"))
                        && actor.clinicId() == null)))
                .thenReturn(List.of());

        mockMvc.perform(get("/appointment/patient/{patientId}", 42L)
                        .with(patientJwt())
                        .header("X-User-ID", "999")
                        .header("X-User-Roles", "SYSTEM_ADMIN")
                        .header("X-Clinic-ID", "999"))
                .andExpect(status().isOk());

        verify(appointmentService).getPatientAppointments(
                eq(42L),
                argThat(actor -> actor.userId() == 42L
                        && actor.roles().equals(java.util.Set.of("PATIENT"))
                        && actor.clinicId() == null));
    }

    @Test
    void patientMutationReachesServiceWithoutCsrfToken() throws Exception {
        when(appointmentService.cancelAppointment(
                eq(100L),
                eq("Schedule changed"),
                argThat((AppointmentActor actor) -> actor.userId() == 42L
                        && actor.roles().equals(java.util.Set.of("PATIENT"))
                        && actor.clinicId() == null)))
                .thenReturn(AppointmentResponse.builder().id(100L).build());

        mockMvc.perform(patch("/appointment/{id}/cancel", 100L)
                        .with(patientJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Schedule changed\"}"))
                .andExpect(status().isOk());

        verify(appointmentService).cancelAppointment(
                eq(100L),
                eq("Schedule changed"),
                argThat((AppointmentActor actor) -> actor.userId() == 42L
                        && actor.roles().equals(java.util.Set.of("PATIENT"))
                        && actor.clinicId() == null));
    }

    @Test
    void patientCannotListClinicPatientIds() throws Exception {
        mockMvc.perform(get("/appointment/clinic/{clinicId}/patients", 7L)
                        .with(patientJwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(appointmentService);
    }

    @Test
    void receptionistCanListClinicPatientIdsWithJwtClinic() throws Exception {
        when(appointmentService.getDistinctPatientIdsByClinicId(
                eq(7L),
                argThat(actor -> actor.userId() == 90L
                        && actor.roles().equals(java.util.Set.of("RECEPTIONIST"))
                        && Long.valueOf(7L).equals(actor.clinicId()))))
                .thenReturn(List.of(42L));

        mockMvc.perform(get("/appointment/clinic/{clinicId}/patients", 7L)
                        .with(jwt()
                                .jwt(token -> token
                                        .subject("90")
                                        .claim("roles", List.of("RECEPTIONIST"))
                                        .claim("clinicId", 7L))
                                .authorities(new SimpleGrantedAuthority(
                                        "ROLE_RECEPTIONIST"))))
                .andExpect(status().isOk());

        verify(appointmentService).getDistinctPatientIdsByClinicId(
                eq(7L),
                argThat(actor -> actor.userId() == 90L
                        && actor.roles().equals(java.util.Set.of("RECEPTIONIST"))
                        && Long.valueOf(7L).equals(actor.clinicId())));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor patientJwt() {
        return jwt()
                .jwt(token -> token
                        .subject("42")
                        .claim("roles", List.of("PATIENT")))
                .authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        AppointmentService appointmentService() {
            return mock(AppointmentService.class);
        }

        @Bean
        AppointmentController appointmentController(
                AppointmentService appointmentService) {
            return new AppointmentController(appointmentService);
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
