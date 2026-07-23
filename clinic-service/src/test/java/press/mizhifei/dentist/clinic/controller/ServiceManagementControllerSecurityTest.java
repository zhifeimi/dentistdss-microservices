package press.mizhifei.dentist.clinic.controller;

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
import press.mizhifei.dentist.clinic.dto.ServiceResponse;
import press.mizhifei.dentist.clinic.service.ServiceManagementService;
import press.mizhifei.dentist.security.RedisAccessTokenJwtDecoder;
import press.mizhifei.dentist.security.ServletBearerTokenFailureHandler;
import press.mizhifei.dentist.security.ServletJwtResourceServerCustomizer;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        ServiceManagementControllerSecurityTest.TestConfig.class,
        ClinicSecurityConfig.class
})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class ServiceManagementControllerSecurityTest {

    private static final String VALID_SERVICE_BODY =
            "{\"clinicId\":%d,\"name\":\"Checkup\",\"durationMinutes\":30,\"price\":100.0}";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ServiceManagementService serviceManagementService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(serviceManagementService);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void forgedIdentityHeadersCannotAuthenticateServiceMutation() throws Exception {
        mockMvc.perform(post("/clinic/service")
                        .header("X-User-ID", "55")
                        .header("X-User-Roles", "CLINIC_ADMIN")
                        .header("X-Clinic-ID", "7")
                        .contentType("application/json")
                        .content(VALID_SERVICE_BODY.formatted(7)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(serviceManagementService);
    }

    @Test
    void patientCannotCreateService() throws Exception {
        mockMvc.perform(post("/clinic/service")
                        .with(patientJwt())
                        .contentType("application/json")
                        .content(VALID_SERVICE_BODY.formatted(7)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(serviceManagementService);
    }

    @Test
    void clinicAdminCreatesServiceForOwnClinic() throws Exception {
        ServiceResponse created = ServiceResponse.builder().id(1).clinicId(7L).build();
        when(serviceManagementService.createService(any())).thenReturn(created);

        mockMvc.perform(post("/clinic/service")
                        .with(clinicAdminJwt(7L))
                        .contentType("application/json")
                        .content(VALID_SERVICE_BODY.formatted(7)))
                .andExpect(status().isOk());

        verify(serviceManagementService).createService(any());
    }

    @Test
    void clinicAdminCannotCreateServiceForAnotherClinic() throws Exception {
        mockMvc.perform(post("/clinic/service")
                        .with(clinicAdminJwt(7L))
                        .contentType("application/json")
                        .content(VALID_SERVICE_BODY.formatted(8)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "Access denied. You can only manage services of your own clinic."));

        verify(serviceManagementService, never()).createService(any());
    }

    @Test
    void systemAdminCreatesServiceForAnyClinic() throws Exception {
        ServiceResponse created = ServiceResponse.builder().id(1).clinicId(8L).build();
        when(serviceManagementService.createService(any())).thenReturn(created);

        mockMvc.perform(post("/clinic/service")
                        .with(systemAdminJwt())
                        .contentType("application/json")
                        .content(VALID_SERVICE_BODY.formatted(8)))
                .andExpect(status().isOk());

        verify(serviceManagementService).createService(any());
    }

    @Test
    void clinicAdminCannotDeleteAnotherClinicsService() throws Exception {
        when(serviceManagementService.getService(5))
                .thenReturn(ServiceResponse.builder().id(5).clinicId(8L).build());

        mockMvc.perform(delete("/clinic/service/{id}", 5)
                        .with(clinicAdminJwt(7L)))
                .andExpect(status().isForbidden());

        verify(serviceManagementService, never()).deleteService(eq(5));
    }

    @Test
    void clinicAdminDeletesOwnClinicsService() throws Exception {
        when(serviceManagementService.getService(5))
                .thenReturn(ServiceResponse.builder().id(5).clinicId(7L).build());

        mockMvc.perform(delete("/clinic/service/{id}", 5)
                        .with(clinicAdminJwt(7L)))
                .andExpect(status().isOk());

        verify(serviceManagementService).deleteService(5);
    }

    @Test
    void serviceCatalogReadsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/clinic/service/clinic/{clinicId}", 7L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(serviceManagementService);

        when(serviceManagementService.getClinicServices(7L, true)).thenReturn(List.of());
        mockMvc.perform(get("/clinic/service/clinic/{clinicId}", 7L)
                        .with(patientJwt()))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor patientJwt() {
        return jwt()
                .jwt(token -> token
                        .subject("42")
                        .claim("roles", List.of("PATIENT")))
                .authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
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
        ServiceManagementService serviceManagementService() {
            return mock(ServiceManagementService.class);
        }

        @Bean
        ServiceManagementController serviceManagementController(
                ServiceManagementService serviceManagementService) {
            return new ServiceManagementController(serviceManagementService);
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
