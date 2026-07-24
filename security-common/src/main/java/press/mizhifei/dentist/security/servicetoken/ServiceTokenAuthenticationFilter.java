package press.mizhifei.dentist.security.servicetoken;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter accepting service credentials on the exact path matchers a
 * target configures. Same activation rules as the reactive factory: matcher
 * match + no user {@code Authorization} header + {@code Bearer} credential in
 * {@link ServiceTokenConstants#HEADER_NAME}. Requests without a service header
 * fall through to the normal user-JWT resource-server chain, so mixed
 * user/service endpoints keep their existing behavior.
 */
public final class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    private final JwtDecoder serviceJwtDecoder;
    private final RequestMatcher requestMatcher;
    private final AuthenticationEntryPoint failureHandler;

    public ServiceTokenAuthenticationFilter(
            JwtDecoder serviceJwtDecoder,
            RequestMatcher requestMatcher,
            AuthenticationEntryPoint failureHandler) {
        this.serviceJwtDecoder = serviceJwtDecoder;
        this.requestMatcher = requestMatcher;
        this.failureHandler = failureHandler;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!requestMatcher.matches(request)
                || StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            filterChain.doFilter(request, response);
            return;
        }
        String serviceAuthorization = request.getHeader(ServiceTokenConstants.HEADER_NAME);
        if (!StringUtils.hasText(serviceAuthorization)
                || !serviceAuthorization.startsWith(ServiceTokenConstants.BEARER_PREFIX)
                || !StringUtils.hasText(
                        serviceAuthorization.substring(ServiceTokenConstants.BEARER_PREFIX.length()))) {
            filterChain.doFilter(request, response);
            return;
        }
        Jwt jwt;
        try {
            jwt = serviceJwtDecoder.decode(
                    serviceAuthorization.substring(ServiceTokenConstants.BEARER_PREFIX.length()));
        } catch (RuntimeException error) {
            SecurityContextHolder.clearContext();
            failureHandler.commence(request, response, failureFor(error));
            return;
        }
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt,
                ServiceTokenGrantedAuthorities.authorities(jwt),
                jwt.getSubject());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Maps decode failures onto the bearer failure contract: configuration
     * unavailability stays a sanitized 503; every other decode failure
     * (unknown kid, forged signature, invalid claims) is an invalid credential
     * and renders as a 401. In Spring Security 7 the decoders raise plain
     * {@code JwtException}s (not {@link AuthenticationException}s), so
     * non-authentication failures are wrapped for the entry point.
     */
    private static AuthenticationException failureFor(RuntimeException error) {
        if (error instanceof AuthenticationException authenticationError) {
            return authenticationError;
        }
        return new BadCredentialsException("Invalid service credential", error);
    }
}
