package press.mizhifei.dentist.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import press.mizhifei.dentist.auth.security.JwtAuthenticationFilter;
import press.mizhifei.dentist.auth.service.AuthCookieService;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthCookieService authCookieService;

    @Value("${springdoc.api-docs.enabled:false}")
    private boolean springdocEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> {
                    csrf.csrfTokenRepository(authCookieService.csrfTokenRepository());
                    csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
                    csrf.requireCsrfProtectionMatcher(this::requiresRefreshCookieCsrfProtection);
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.POST,
                                    "/auth/login",
                                    "/auth/refresh",
                                    "/auth/logout",
                                    "/auth/signup",
                                    "/auth/signup/clinic/staff",
                                    "/auth/signup/clinic/admin",
                                    "/auth/signup/verify/code/resend",
                                    "/auth/signup/verify/code",
                                    "/oauth2/token")
                            .permitAll();
                    auth.requestMatchers(HttpMethod.GET,
                                    "/auth/csrf",
                                    "/auth/oauth2/jwks",
                                    "/oauth2/nonce",
                                    "/actuator/health",
                                    "/actuator/health/**")
                            .permitAll();
                    if (springdocEnabled) {
                        auth.requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html")
                                .permitAll();
                    }
                    auth.anyRequest().authenticated();
                });

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private boolean requiresRefreshCookieCsrfProtection(HttpServletRequest request) {
        if (!CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                || !StringUtils.hasText(authCookieService.readRefreshToken(request))) {
            return false;
        }
        String requestPath = pathWithinApplication(request);
        return "/auth/refresh".equals(requestPath) || "/auth/logout".equals(requestPath);
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return StringUtils.hasLength(contextPath) && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration)
            throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
