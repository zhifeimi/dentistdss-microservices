package press.mizhifei.dentist.auth.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import press.mizhifei.dentist.auth.config.SecurityConfig;
import press.mizhifei.dentist.auth.dto.AuthResponse;
import press.mizhifei.dentist.auth.dto.SessionTokens;
import press.mizhifei.dentist.auth.security.CustomUserDetailsService;
import press.mizhifei.dentist.auth.security.JwtAuthenticationFilter;
import press.mizhifei.dentist.auth.security.JwtTokenProvider;
import press.mizhifei.dentist.auth.service.AuthCookieService;
import press.mizhifei.dentist.auth.service.AuthService;
import press.mizhifei.dentist.auth.service.AuthSessionService;
import press.mizhifei.dentist.auth.service.SecurityStateService;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        AuthCsrfSecurityIntegrationTest.TestConfig.class,
        SecurityConfig.class
})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class AuthCsrfSecurityIntegrationTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private AuthSessionService authSessionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(authSessionService);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void csrfBootstrapIssuesReadableTokenAndAllowsNoStoreCrossOriginTransport() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/csrf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(AuthCookieService.CSRF_HEADER))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        Cookie csrfCookie = result.getResponse().getCookie(AuthCookieService.CSRF_COOKIE);
        assertNotNull(csrfCookie);
        assertEquals(result.getResponse().getHeader(AuthCookieService.CSRF_HEADER), csrfCookie.getValue());
    }

    @Test
    void refreshCookieWithoutXsrfHeaderIsRejectedBeforeController() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .cookie(refreshCookie(), csrfCookie("csrf-token")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(authSessionService);
    }

    @Test
    void refreshCookieWithMismatchedXsrfHeaderIsRejectedBeforeController() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .cookie(refreshCookie(), csrfCookie("csrf-token"))
                        .header(AuthCookieService.CSRF_HEADER, "different-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(authSessionService);
    }

    @Test
    void matchingXsrfCookieAndHeaderReachRefreshController() throws Exception {
        when(authSessionService.refresh("refresh-token"))
                .thenReturn(new SessionTokens(
                        AuthResponse.builder().accessToken("access-token").build(),
                        "rotated-refresh-token",
                        "rotated-csrf-token"));

        mockMvc.perform(post("/auth/refresh")
                        .cookie(refreshCookie(), csrfCookie("csrf-token"))
                        .header(AuthCookieService.CSRF_HEADER, "csrf-token")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
                .andExpect(status().isOk());

        verify(authSessionService).refresh("refresh-token");
    }

    @Test
    void bearerlessLogoutWithoutRefreshCookieDoesNotRequireXsrfToken() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk());

        verifyNoInteractions(authSessionService);
    }

    private Cookie refreshCookie() {
        return new Cookie(AuthCookieService.DEVELOPMENT_REFRESH_COOKIE, "refresh-token");
    }

    private Cookie csrfCookie(String value) {
        return new Cookie(AuthCookieService.CSRF_COOKIE, value);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfig {

        @Bean
        AuthCookieService authCookieService() {
            AuthCookieService authCookieService = mock(AuthCookieService.class);
            CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
            repository.setCookieName(AuthCookieService.CSRF_COOKIE);
            repository.setHeaderName(AuthCookieService.CSRF_HEADER);
            repository.setCookiePath("/");
            when(authCookieService.csrfTokenRepository()).thenReturn(repository);
            when(authCookieService.readRefreshToken(any(HttpServletRequest.class)))
                    .thenAnswer(invocation -> {
                        Cookie[] cookies = invocation.getArgument(0, HttpServletRequest.class).getCookies();
                        if (cookies == null) {
                            return null;
                        }
                        return Arrays.stream(cookies)
                                .filter(cookie -> AuthCookieService.DEVELOPMENT_REFRESH_COOKIE
                                        .equals(cookie.getName()))
                                .map(Cookie::getValue)
                                .findFirst()
                                .orElse(null);
                    });
            return authCookieService;
        }

        @Bean
        SecurityStateService securityStateService() {
            return mock(SecurityStateService.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(
                SecurityStateService securityStateService) {
            return new JwtAuthenticationFilter(
                    mock(JwtTokenProvider.class),
                    mock(CustomUserDetailsService.class),
                    securityStateService);
        }

        @Bean
        AuthController authController(
                AuthService authService,
                AuthSessionService authSessionService,
                AuthCookieService authCookieService,
                SecurityStateService securityStateService) {
            return new AuthController(
                    authService,
                    authSessionService,
                    authCookieService,
                    securityStateService);
        }
    }
}
