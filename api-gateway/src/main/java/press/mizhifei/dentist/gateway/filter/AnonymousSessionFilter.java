package press.mizhifei.dentist.gateway.filter;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import press.mizhifei.dentist.gateway.config.GatewayRequestMatchers;
import press.mizhifei.dentist.gateway.security.GenAIServiceTokenIssuer;
import press.mizhifei.dentist.gateway.service.AnonymousSessionProofService;
import press.mizhifei.dentist.gateway.service.AnonymousSessionService;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Removes caller-supplied identity headers and adds metadata derived only from
 * the verified Spring Security JWT.
 */
@Component
@RequiredArgsConstructor
public class AnonymousSessionFilter implements GlobalFilter, Ordered {

    private static final String SESSION_ID_HEADER = "X-Session-ID";
    private static final String ANONYMOUS_PROOF_HEADER = "X-Gateway-Anonymous-Proof";
    private static final String SERVICE_AUTH_HEADER = GenAIServiceTokenIssuer.HEADER_NAME;
    /**
     * Header carrying the shared inter-service credential defined by
     * security-common's {@code ServiceTokenConstants#HEADER_NAME}. The literal
     * is duplicated here on purpose: the gateway must not depend on
     * security-common, and any caller-supplied value on this header is always
     * forged — service credentials are only ever minted cluster-internally by
     * the calling service, never through the gateway.
     */
    private static final String SERVICE_CREDENTIAL_HEADER = "X-Service-Authorization";
    private static final String USER_ID_HEADER = "X-User-ID";
    private static final String USER_EMAIL_HEADER = "X-User-Email";
    private static final String USER_ROLES_HEADER = "X-User-Roles";
    private static final String CLINIC_ID_HEADER = "X-Clinic-ID";
    private static final List<String> TRUSTED_HEADERS = List.of(
            SESSION_ID_HEADER,
            ANONYMOUS_PROOF_HEADER,
            SERVICE_AUTH_HEADER,
            SERVICE_CREDENTIAL_HEADER,
            USER_ID_HEADER,
            USER_EMAIL_HEADER,
            USER_ROLES_HEADER,
            CLINIC_ID_HEADER);

    private final AnonymousSessionService anonymousSessionService;
    private final AnonymousSessionProofService anonymousSessionProofService;
    private final GenAIServiceTokenIssuer serviceTokenIssuer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String existingSessionId = exchange.getRequest().getHeaders().getFirst(SESSION_ID_HEADER);
        ServerWebExchange sanitizedExchange = sanitizeIdentityHeaders(exchange);
        String path = sanitizedExchange.getRequest().getPath().value();

        if (shouldSkipSessionManagement(path)) {
            return chain.filter(sanitizedExchange);
        }

        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .filter(authentication -> authentication.isAuthenticated())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> authentication
                        .map(value -> addAuthenticatedContext(
                                sanitizedExchange,
                                chain,
                                value))
                        .orElseGet(() -> addAnonymousContext(
                                sanitizedExchange,
                                chain,
                                existingSessionId)));
    }

    private Mono<Void> addAuthenticatedContext(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            JwtAuthenticationToken authentication) {
        String sessionId = authenticatedSessionId(authentication);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(SESSION_ID_HEADER, sessionId);
                    setHeaderIfPresent(headers, USER_ID_HEADER, authentication.getToken().getSubject());
                    setHeaderIfPresent(
                            headers,
                            USER_EMAIL_HEADER,
                            authentication.getToken().getClaimAsString("email"));
                    setHeaderIfPresent(headers, USER_ROLES_HEADER, rolesClaim(authentication));
                    setHeaderIfPresent(
                            headers,
                            CLINIC_ID_HEADER,
                            claimAsString(authentication, "clinicId"));
                })
                .build();
        exchange.getResponse().getHeaders().set(SESSION_ID_HEADER, sessionId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    private Mono<Void> addAnonymousContext(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            String existingSessionId) {
        if (!GatewayRequestMatchers.isAnonymousHelp(exchange.getRequest())
                || StringUtils.hasText(exchange.getRequest().getHeaders()
                        .getFirst(HttpHeaders.AUTHORIZATION))) {
            return chain.filter(exchange);
        }

        return anonymousSessionService.getOrCreateAnonymousSession(
                        existingSessionId,
                        exchange.getRequest())
                .flatMap(sessionId -> anonymousSessionProofService.issueProof(
                                exchange.getRequest(),
                                sessionId)
                        .flatMap(proof -> serviceTokenIssuer.issueAnonymousHelpToken()
                                .flatMap(serviceToken -> {
                                    ServerHttpRequest request = exchange.getRequest().mutate()
                                            .headers(headers -> {
                                                headers.set(SESSION_ID_HEADER, sessionId);
                                                headers.set(ANONYMOUS_PROOF_HEADER, proof);
                                                headers.set(
                                                        SERVICE_AUTH_HEADER,
                                                        "Bearer " + serviceToken);
                                            })
                                            .build();
                                    exchange.getResponse().getHeaders().set(
                                            SESSION_ID_HEADER,
                                            sessionId);
                                    return chain.filter(exchange.mutate()
                                            .request(request)
                                            .build());
                                })));
    }

    private ServerWebExchange sanitizeIdentityHeaders(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> TRUSTED_HEADERS.forEach(headers::remove))
                .build();
        return exchange.mutate().request(request).build();
    }

    private String authenticatedSessionId(JwtAuthenticationToken authentication) {
        String subject = authentication.getToken().getSubject();
        String sessionFamilyId = authentication.getToken().getClaimAsString("sessionFamilyId");
        if (!StringUtils.hasText(subject) || !StringUtils.hasText(sessionFamilyId)) {
            throw new IllegalStateException(
                    "Verified access token requires subject and session family claims");
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((subject + (char) 0x1F + sessionFamilyId)
                            .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String rolesClaim(JwtAuthenticationToken authentication) {
        Object claim = authentication.getToken().getClaim("roles");
        if (claim instanceof Collection<?> roles) {
            return roles.stream()
                    .map(String::valueOf)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining(","));
        }
        return claim == null ? null : String.valueOf(claim);
    }

    private String claimAsString(JwtAuthenticationToken authentication, String claimName) {
        Object claim = authentication.getToken().getClaim(claimName);
        return claim == null ? null : String.valueOf(claim);
    }

    private void setHeaderIfPresent(HttpHeaders headers, String name, String value) {
        if (StringUtils.hasText(value)) {
            headers.set(name, value);
        }
    }

    private boolean shouldSkipSessionManagement(String path) {
        return path.equals("/api/auth")
                || path.startsWith("/api/auth/")
                || path.equals("/oauth2")
                || path.startsWith("/oauth2/")
                || path.equals("/actuator")
                || path.startsWith("/actuator/")
                || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui/")
                || path.endsWith("/v3/api-docs");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
