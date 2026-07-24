package press.mizhifei.dentist.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import press.mizhifei.dentist.auth.config.SecurityConfig;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.ApprovalRequestResponse;
import press.mizhifei.dentist.auth.security.CustomUserDetailsService;
import press.mizhifei.dentist.auth.security.JwtAuthenticationFilter;
import press.mizhifei.dentist.auth.security.JwtTokenProvider;
import press.mizhifei.dentist.auth.security.UserPrincipal;
import press.mizhifei.dentist.auth.service.AuthCookieService;
import press.mizhifei.dentist.auth.service.SecurityStateService;
import press.mizhifei.dentist.auth.service.UserApprovalService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        UserApprovalControllerSecurityTest.TestConfig.class,
        SecurityConfig.class
})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class UserApprovalControllerSecurityTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private UserApprovalService userApprovalService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(userApprovalService);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void unauthenticatedRequestsCannotReadApprovals() throws Exception {
        mockMvc.perform(get("/auth/approval/pending"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userApprovalService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATIENT", "DENTIST", "RECEPTIONIST"})
    void nonAdministrativeAuthoritiesCannotReviewApprovals(String authority) throws Exception {
        mockMvc.perform(post("/auth/approval/{requestId}/review", 7)
                        .with(authentication(reviewerAuthentication(authority)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"reviewNotes\":\"ok\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userApprovalService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "CLINIC_ADMIN"})
    void administrativeAuthoritiesUseAuthenticatedReviewerIdentity(String authority)
            throws Exception {
        when(userApprovalService.reviewApprovalRequest(eq(7), any(), eq(100L)))
                .thenReturn(ApiResponse.success(
                        ApprovalRequestResponse.builder().id(7).build()));

        mockMvc.perform(post("/auth/approval/{requestId}/review", 7)
                        .with(authentication(reviewerAuthentication(authority)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"reviewNotes\":\"ok\",\"reviewedBy\":999}"))
                .andExpect(status().isOk());

        verify(userApprovalService).reviewApprovalRequest(
                eq(7),
                argThat(request -> Boolean.TRUE.equals(request.getApproved())
                        && "ok".equals(request.getReviewNotes())),
                eq(100L));
    }

    @Test
    void administrativeAuthorityCanReadOnlyThroughAuthenticatedReviewer() throws Exception {
        when(userApprovalService.getPendingApprovalRequestsForReviewer(100L))
                .thenReturn(List.of());

        mockMvc.perform(get("/auth/approval/pending")
                        .with(authentication(reviewerAuthentication("SYSTEM_ADMIN"))))
                .andExpect(status().isOk());

        verify(userApprovalService).getPendingApprovalRequestsForReviewer(100L);
    }

    private UsernamePasswordAuthenticationToken reviewerAuthentication(String authority) {
        SimpleGrantedAuthority grantedAuthority = new SimpleGrantedAuthority(authority);
        UserPrincipal principal = UserPrincipal.builder()
                .id(100L)
                .email("reviewer@example.com")
                .password("encoded-password")
                .authorities(List.of(grantedAuthority))
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestConfig {

        @Bean
        UserApprovalService userApprovalService() {
            return mock(UserApprovalService.class);
        }

        @Bean
        AuthCookieService authCookieService() {
            return new AuthCookieService();
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(
                    mock(JwtTokenProvider.class),
                    mock(CustomUserDetailsService.class),
                    mock(SecurityStateService.class));
        }

        @Bean
        UserApprovalController userApprovalController(
                UserApprovalService userApprovalService) {
            return new UserApprovalController(userApprovalService);
        }
    }
}
