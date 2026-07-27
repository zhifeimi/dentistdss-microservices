package press.mizhifei.dentist.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import press.mizhifei.dentist.auth.service.SecurityStateService;

import java.io.IOException;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final SecurityStateService securityStateService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String jwt = getJwtFromRequest(request);
        if (!StringUtils.hasText(jwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        JwtTokenProvider.AccessTokenClaims claims;
        try {
            claims = tokenProvider.getAccessTokenClaims(jwt);
        } catch (RuntimeException ex) {
            log.debug("Rejected bearer token: {}", ex.getClass().getSimpleName());
            reject(response);
            return;
        }

        try {
            SecurityStateService.AccountSecurityState accountState = securityStateService
                    .readAccountState(claims.userId())
                    .orElseThrow(SecurityStateService.SecurityStateUnavailableException::new);
            if (!accountState.active()
                    || accountState.securityVersion() != claims.securityVersion()) {
                reject(response);
                return;
            }

            SecurityStateService.FamilySecurityState familyState = securityStateService
                    .readFamilyState(claims.userId(), claims.sessionFamilyId())
                    .orElseThrow(SecurityStateService.SecurityStateUnavailableException::new);
            if (familyState != SecurityStateService.FamilySecurityState.ACTIVE) {
                reject(response);
                return;
            }

            UserPrincipal userPrincipal = (UserPrincipal) customUserDetailsService
                    .loadUserById(claims.userId());
            if (userPrincipal.getSecurityVersion() != claims.securityVersion()
                    || !userPrincipal.isEnabled()
                    || !userPrincipal.isAccountNonExpired()
                    || !userPrincipal.isCredentialsNonExpired()
                    || !userPrincipal.isAccountNonLocked()) {
                reject(response);
                return;
            }
            userPrincipal.setSessionFamilyId(claims.sessionFamilyId());

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userPrincipal, null, userPrincipal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (DataAccessException | SecurityStateService.SecurityStateUnavailableException ex) {
            SecurityContextHolder.clearContext();
            log.error("Authentication state is unavailable", ex);
            unavailable(response);
            return;
        } catch (RuntimeException ex) {
            log.debug("Rejected bearer token: {}", ex.getClass().getSimpleName());
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = pathWithinApplication(request);
        return ("POST".equals(request.getMethod())
                && "/auth/refresh".equals(requestPath))
                || ("GET".equals(request.getMethod())
                && "/auth/csrf".equals(requestPath));
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return StringUtils.hasLength(contextPath) && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;
    }

    private void reject(HttpServletResponse response) throws IOException {
        writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid bearer token");
    }

    private void unavailable(HttpServletResponse response) throws IOException {
        writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Authentication state unavailable");
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
