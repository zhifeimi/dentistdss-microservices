package press.mizhifei.dentist.genai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.WebFilterChainServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.util.StringUtils;
import press.mizhifei.dentist.genai.security.GenAIServiceJwtDecoder;
import press.mizhifei.dentist.security.ReactiveBearerTokenFailureHandler;
import press.mizhifei.dentist.security.ReactiveJwtResourceServerCustomizer;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class GenAISecurityConfig {

    @Bean
    SecurityWebFilterChain genAiSecurityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtResourceServerCustomizer resourceServerCustomizer,
            GenAIServiceJwtDecoder serviceJwtDecoder,
            ReactiveBearerTokenFailureHandler failureHandler,
            @Value("${springdoc.api-docs.enabled:false}") boolean springdocEnabled) {
        // Stateless chain: chatbot endpoints take bearer user JWTs (or the
        // verified gateway service credential for anonymous help) — credentials
        // are attached explicitly, never auto-attached by a browser, so no
        // request requires CSRF protection (CodeQL java/spring-disabled-csrf:
        // match nothing rather than disabling the filter).
        http.csrf(csrf -> csrf.requireCsrfProtectionMatcher(
                        exchange -> ServerWebExchangeMatcher.MatchResult.notMatch()))
                .cors(Customizer.withDefaults())
                .authorizeExchange(authorize -> {
                    authorize.pathMatchers(HttpMethod.GET,
                                    "/actuator/health", "/actuator/health/**")
                            .permitAll();
                    authorize.pathMatchers(HttpMethod.POST, "/genai/chatbot/help")
                            .authenticated();
                    authorize.pathMatchers(HttpMethod.POST,
                                    "/genai/chatbot/receptionist")
                            .hasRole("PATIENT");
                    authorize.pathMatchers(HttpMethod.POST,
                                    "/genai/chatbot/aidentist")
                            .hasAnyRole("DENTIST", "CLINIC_ADMIN");
                    authorize.pathMatchers(HttpMethod.POST,
                                    "/genai/chatbot/triage")
                            .hasAnyRole("PATIENT", "DENTIST", "RECEPTIONIST");
                    authorize.pathMatchers(HttpMethod.POST,
                                    "/genai/chatbot/documentation/summarize")
                            .hasAnyRole("DENTIST", "CLINIC_ADMIN", "RECEPTIONIST");
                    if (springdocEnabled) {
                        authorize.pathMatchers(
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**")
                                .authenticated();
                    }
                    authorize.anyExchange().denyAll();
                })
                .addFilterAt(
                        serviceAuthenticationFilter(serviceJwtDecoder, failureHandler),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .oauth2ResourceServer(resourceServerCustomizer);
        return http.build();
    }

    private AuthenticationWebFilter serviceAuthenticationFilter(
            GenAIServiceJwtDecoder serviceJwtDecoder,
            ReactiveBearerTokenFailureHandler failureHandler) {
        JwtReactiveAuthenticationManager jwtManager =
                new JwtReactiveAuthenticationManager(serviceJwtDecoder);
        jwtManager.setJwtAuthenticationConverter(jwt -> Mono.just(
                new JwtAuthenticationToken(
                        jwt,
                        List.of(new SimpleGrantedAuthority(
                                GenAIServiceJwtDecoder.AUTHORITY)),
                        jwt.getSubject())));
        ReactiveAuthenticationManager authenticationManager = jwtManager;
        AuthenticationWebFilter filter = new AuthenticationWebFilter(authenticationManager);
        filter.setServerAuthenticationConverter(exchange -> {
            if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())
                    || !"/genai/chatbot/help".equals(
                            exchange.getRequest().getURI().getRawPath())
                    || StringUtils.hasText(exchange.getRequest().getHeaders()
                            .getFirst(HttpHeaders.AUTHORIZATION))) {
                return Mono.empty();
            }
            String serviceAuthorization = exchange.getRequest().getHeaders()
                    .getFirst(GenAIServiceJwtDecoder.HEADER_NAME);
            if (!StringUtils.hasText(serviceAuthorization)
                    || !serviceAuthorization.startsWith("Bearer ")
                    || !StringUtils.hasText(serviceAuthorization.substring(7))) {
                return Mono.empty();
            }
            return Mono.just(new BearerTokenAuthenticationToken(
                    serviceAuthorization.substring(7)));
        });
        filter.setAuthenticationFailureHandler(failureHandler);
        filter.setAuthenticationSuccessHandler(
                new WebFilterChainServerAuthenticationSuccessHandler());
        return filter;
    }
}
