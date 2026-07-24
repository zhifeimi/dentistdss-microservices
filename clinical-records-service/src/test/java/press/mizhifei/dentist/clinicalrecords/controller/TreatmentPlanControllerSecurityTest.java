package press.mizhifei.dentist.clinicalrecords.controller;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import press.mizhifei.dentist.clinicalrecords.config.ClinicalRecordsSecurityConfig;
import press.mizhifei.dentist.clinicalrecords.dto.TreatmentPlanResponse;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;
import press.mizhifei.dentist.clinicalrecords.service.TreatmentPlanService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        TreatmentPlanControllerSecurityTest.TestConfig.class,
        ClinicalRecordsSecurityConfig.class
})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class TreatmentPlanControllerSecurityTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private TreatmentPlanService treatmentPlanService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(treatmentPlanService);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void forgedIdentityHeadersCannotAuthenticateClinicalRoute() throws Exception {
        mockMvc.perform(get("/clinical-records/treatment-plan/{id}", 42)
                        .header("X-User-ID", "42")
                        .header("X-User-Roles", "SYSTEM_ADMIN")
                        .header("X-Clinic-ID", "7"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(treatmentPlanService);
    }

    @Test
    void patientCannotStartTreatmentPlan() throws Exception {
        mockMvc.perform(post("/clinical-records/treatment-plan/{id}/start", 100)
                        .with(patientJwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(treatmentPlanService);
    }

    @Test
    void dentistMutationReachesServiceWithoutCsrfToken() throws Exception {
        TreatmentPlanResponse response = TreatmentPlanResponse.builder().id(100).build();
        when(treatmentPlanService.startTreatmentPlan(
                argThat((ClinicalRecordsActor actor) -> actor.userId() == 84L
                        && actor.roles().equals(java.util.Set.of("DENTIST"))
                        && Long.valueOf(7L).equals(actor.clinicId())),
                eq(100)))
                .thenReturn(response);

        mockMvc.perform(post("/clinical-records/treatment-plan/{id}/start", 100)
                        .with(dentistJwt()))
                .andExpect(status().isOk());

        verify(treatmentPlanService).startTreatmentPlan(
                argThat((ClinicalRecordsActor actor) -> actor.userId() == 84L
                        && actor.roles().equals(java.util.Set.of("DENTIST"))
                        && Long.valueOf(7L).equals(actor.clinicId())),
                eq(100));
    }

    @Test
    void receptionistCannotReadTreatmentPlan() throws Exception {
        mockMvc.perform(get("/clinical-records/treatment-plan/{id}", 100)
                        .with(jwt()
                                .jwt(token -> token
                                        .subject("90")
                                        .claim("roles", List.of("RECEPTIONIST"))
                                        .claim("clinicId", 7L))
                                .authorities(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(treatmentPlanService);
    }

    @Test
    void patientJwtControlsActorDespiteForgedHeaders() throws Exception {
        TreatmentPlanResponse response = TreatmentPlanResponse.builder().id(42).build();
        when(treatmentPlanService.getTreatmentPlan(
                argThat((ClinicalRecordsActor actor) -> actor.userId() == 42L
                        && actor.roles().equals(java.util.Set.of("PATIENT"))
                        && actor.clinicId() == null),
                eq(42)))
                .thenReturn(response);

        mockMvc.perform(get("/clinical-records/treatment-plan/{id}", 42)
                        .with(patientJwt())
                        .header("X-User-ID", "999")
                        .header("X-User-Roles", "SYSTEM_ADMIN")
                        .header("X-Clinic-ID", "999"))
                .andExpect(status().isOk());

        verify(treatmentPlanService).getTreatmentPlan(
                argThat((ClinicalRecordsActor actor) -> actor.userId() == 42L
                        && actor.roles().equals(java.util.Set.of("PATIENT"))
                        && actor.clinicId() == null),
                eq(42));
    }

    @Test
    void authenticatedUnknownRouteIsDenied() throws Exception {
        mockMvc.perform(get("/not-a-clinical-route").with(patientJwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(treatmentPlanService);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor dentistJwt() {
        return jwt()
                .jwt(token -> token
                        .subject("84")
                        .claim("roles", List.of("DENTIST"))
                        .claim("clinicId", 7L))
                .authorities(new SimpleGrantedAuthority("ROLE_DENTIST"));
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
        TreatmentPlanService treatmentPlanService() {
            return mock(TreatmentPlanService.class);
        }

        @Bean
        TreatmentPlanController treatmentPlanController(TreatmentPlanService treatmentPlanService) {
            return new TreatmentPlanController(treatmentPlanService);
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
