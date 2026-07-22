package press.mizhifei.dentist.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import press.mizhifei.dentist.auth.service.SecurityStateService;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private JwtTokenProvider tokenProvider;
    private CustomUserDetailsService userDetailsService;
    private SecurityStateService securityStateService;
    private JwtAuthenticationFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        tokenProvider = mock(JwtTokenProvider.class);
        userDetailsService = mock(CustomUserDetailsService.class);
        securityStateService = mock(SecurityStateService.class);
        filter = new JwtAuthenticationFilter(tokenProvider, userDetailsService, securityStateService);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(mock(PrintWriter.class));
        chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer signed-token");
        when(tokenProvider.getAccessTokenClaims("signed-token"))
                .thenReturn(new JwtTokenProvider.AccessTokenClaims(42L, 3L, "family-1"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void skipsBearerValidationForRefreshCookieRecovery() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getServletPath()).thenReturn("/auth/refresh");

        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    void bearerlessLogoutContinuesForCookieRevocation() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getMethod()).thenReturn("POST");
        when(request.getServletPath()).thenReturn("/auth/logout");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void missingAccountStateReturnsServiceUnavailable() throws Exception {
        when(securityStateService.readAccountState(42L)).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void rejectsStaleAccountState() throws Exception {
        when(securityStateService.readAccountState(42L))
                .thenReturn(Optional.of(new SecurityStateService.AccountSecurityState(4L, true)));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(securityStateService, never()).readFamilyState(42L, "family-1");
    }

    @Test
    void rejectsInactiveAccountState() throws Exception {
        when(securityStateService.readAccountState(42L))
                .thenReturn(Optional.of(new SecurityStateService.AccountSecurityState(3L, false)));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(securityStateService, never()).readFamilyState(42L, "family-1");
    }

    @Test
    void rejectsRevokedFamilyState() throws Exception {
        when(securityStateService.readAccountState(42L))
                .thenReturn(Optional.of(new SecurityStateService.AccountSecurityState(3L, true)));
        when(securityStateService.readFamilyState(42L, "family-1"))
                .thenReturn(Optional.of(SecurityStateService.FamilySecurityState.REVOKED));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(userDetailsService, never()).loadUserById(42L);
    }

    @Test
    void returnsServiceUnavailableWhenRedisStateCannotBeRead() throws Exception {
        when(securityStateService.readAccountState(42L))
                .thenThrow(new RedisConnectionFailureException("unavailable"));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(response, never()).sendError(
                HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Authentication state unavailable");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void authenticatesOnlyCurrentActiveAccountAndFamily() throws Exception {
        when(securityStateService.readAccountState(42L))
                .thenReturn(Optional.of(new SecurityStateService.AccountSecurityState(3L, true)));
        when(securityStateService.readFamilyState(42L, "family-1"))
                .thenReturn(Optional.of(SecurityStateService.FamilySecurityState.ACTIVE));
        UserPrincipal principal = activePrincipal(3L);
        when(userDetailsService.loadUserById(42L)).thenReturn(principal);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("family-1", principal.getSessionFamilyId());
    }

    @Test
    void rejectsDatabasePrincipalThatDoesNotMatchAuthoritativeVersion() throws Exception {
        when(securityStateService.readAccountState(42L))
                .thenReturn(Optional.of(new SecurityStateService.AccountSecurityState(3L, true)));
        when(securityStateService.readFamilyState(42L, "family-1"))
                .thenReturn(Optional.of(SecurityStateService.FamilySecurityState.ACTIVE));
        when(userDetailsService.loadUserById(42L)).thenReturn(activePrincipal(2L));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void propagatesDownstreamRuntimeFailuresAfterAuthentication() throws Exception {
        when(securityStateService.readAccountState(42L))
                .thenReturn(Optional.of(new SecurityStateService.AccountSecurityState(3L, true)));
        when(securityStateService.readFamilyState(42L, "family-1"))
                .thenReturn(Optional.of(SecurityStateService.FamilySecurityState.ACTIVE));
        when(userDetailsService.loadUserById(42L)).thenReturn(activePrincipal(3L));
        IllegalStateException failure = new IllegalStateException("downstream failure");
        doThrow(failure).when(chain).doFilter(request, response);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> filter.doFilterInternal(request, response, chain));

        assertSame(failure, thrown);
    }

    private UserPrincipal activePrincipal(long securityVersion) {
        return UserPrincipal.builder()
                .id(42L)
                .email("patient@example.com")
                .securityVersion(securityVersion)
                .authorities(List.of(new SimpleGrantedAuthority("PATIENT")))
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
    }
}
