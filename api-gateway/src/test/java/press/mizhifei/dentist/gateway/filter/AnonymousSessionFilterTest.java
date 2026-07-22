package press.mizhifei.dentist.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import press.mizhifei.dentist.gateway.security.GenAIServiceTokenIssuer;
import press.mizhifei.dentist.gateway.service.AnonymousSessionProofService;
import press.mizhifei.dentist.gateway.service.AnonymousSessionService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnonymousSessionFilterTest {

    private static final String SERVER_SESSION_ID = "S".repeat(43);
    private static final String SERVER_PROOF = "P".repeat(43);

    private AnonymousSessionService anonymousSessionService;
    private AnonymousSessionProofService anonymousSessionProofService;
    private GenAIServiceTokenIssuer serviceTokenIssuer;
    private AnonymousSessionFilter filter;

    @BeforeEach
    void setUp() {
        anonymousSessionService = mock(AnonymousSessionService.class);
        anonymousSessionProofService = mock(AnonymousSessionProofService.class);
        serviceTokenIssuer = mock(GenAIServiceTokenIssuer.class);
        when(serviceTokenIssuer.issueAnonymousHelpToken())
                .thenReturn(Mono.just("service-token"));
        filter = new AnonymousSessionFilter(
                anonymousSessionService,
                anonymousSessionProofService,
                serviceTokenIssuer);
    }

    @Test
    void stripsSpoofedIdentityHeadersAndIssuesSessionForExactAnonymousHelp() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/genai/chatbot/help")
                        .header("X-Session-ID", "attacker-session")
                        .header("X-Gateway-Anonymous-Proof", "attacker-proof")
                        .header(GenAIServiceTokenIssuer.HEADER_NAME, "Bearer attacker-token")
                        .header("X-User-ID", "999")
                        .header("X-User-Email", "attacker@example.com")
                        .header("X-User-Roles", "SYSTEM_ADMIN")
                        .header("X-Clinic-ID", "999")
                        .build());
        when(anonymousSessionService.getOrCreateAnonymousSession(
                eq("attacker-session"),
                any(ServerHttpRequest.class)))
                .thenReturn(Mono.just(SERVER_SESSION_ID));
        when(anonymousSessionProofService.issueProof(
                any(ServerHttpRequest.class),
                eq(SERVER_SESSION_ID)))
                .thenReturn(Mono.just(SERVER_PROOF));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstream.set(filteredExchange);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        HttpHeaders headers = downstream.get().getRequest().getHeaders();
        assertEquals(SERVER_SESSION_ID, headers.getFirst("X-Session-ID"));
        assertEquals(SERVER_PROOF, headers.getFirst("X-Gateway-Anonymous-Proof"));
        assertEquals("Bearer service-token",
                headers.getFirst(GenAIServiceTokenIssuer.HEADER_NAME));
        assertNull(headers.getFirst("X-User-ID"));
        assertNull(headers.getFirst("X-User-Email"));
        assertNull(headers.getFirst("X-User-Roles"));
        assertNull(headers.getFirst("X-Clinic-ID"));
        assertEquals(SERVER_SESSION_ID,
                exchange.getResponse().getHeaders().getFirst("X-Session-ID"));
        assertNull(exchange.getResponse().getHeaders().getFirst(
                "X-Gateway-Anonymous-Proof"));

        var requestCaptor = forClass(ServerHttpRequest.class);
        verify(anonymousSessionService).getOrCreateAnonymousSession(
                eq("attacker-session"),
                requestCaptor.capture());
        assertNull(requestCaptor.getValue().getHeaders().getFirst("X-Session-ID"));
        assertNull(requestCaptor.getValue().getHeaders().getFirst(
                "X-Gateway-Anonymous-Proof"));
        assertNull(requestCaptor.getValue().getHeaders().getFirst("X-User-ID"));
        verify(anonymousSessionProofService).issueProof(
                requestCaptor.getValue(),
                SERVER_SESSION_ID);
    }

    @Test
    void derivesAuthenticatedSessionFromVerifiedJwtWithoutRedisAllocation() {
        Jwt jwt = jwt("42", "token-id-1", "family-1");
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("PATIENT")));
        ServerWebExchange exchange = authenticatedExchange(
                "/api/appointment/list",
                authentication);
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstream.set(filteredExchange);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        HttpHeaders headers = downstream.get().getRequest().getHeaders();
        assertEquals(expectedAuthenticatedSession("42", "family-1"),
                headers.getFirst("X-Session-ID"));
        assertEquals("42", headers.getFirst("X-User-ID"));
        assertEquals("patient@example.com", headers.getFirst("X-User-Email"));
        assertEquals("PATIENT,DENTIST", headers.getFirst("X-User-Roles"));
        assertEquals("7", headers.getFirst("X-Clinic-ID"));
        assertNull(headers.getFirst("X-Gateway-Anonymous-Proof"));
        verify(anonymousSessionService, never()).getOrCreateAnonymousSession(
                any(),
                any(ServerHttpRequest.class));
    }

    @Test
    void authenticatedHelpCannotCreateAnonymousReusableMarker() {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt("42", "token-id-2", "family-2"),
                List.of(new SimpleGrantedAuthority("PATIENT")));
        ServerWebExchange exchange = authenticatedExchange(
                "/api/genai/chatbot/help",
                authentication);
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstream.set(filteredExchange);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(expectedAuthenticatedSession("42", "family-2"),
                downstream.get().getRequest().getHeaders().getFirst("X-Session-ID"));
        verify(anonymousSessionService, never()).getOrCreateAnonymousSession(
                any(),
                any(ServerHttpRequest.class));
    }

    @Test
    void authenticatedBearerHelpPreservesAuthorizationAndDoesNotMintServiceToken() {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt("42", "token-id-3", "family-3"),
                List.of(new SimpleGrantedAuthority("PATIENT")));
        ServerWebExchange exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/genai/chatbot/help")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer end-user-token")
                                .header(GenAIServiceTokenIssuer.HEADER_NAME,
                                        "Bearer attacker-token")
                                .build())
                .mutate()
                .principal(Mono.just(authentication))
                .build();
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, filteredExchange -> {
                    downstream.set(filteredExchange);
                    return Mono.empty();
                }))
                .verifyComplete();

        assertEquals("Bearer end-user-token", downstream.get().getRequest()
                .getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(downstream.get().getRequest().getHeaders()
                .getFirst(GenAIServiceTokenIssuer.HEADER_NAME));
        verify(serviceTokenIssuer, never()).issueAnonymousHelpToken();
        verifyNoAnonymousSessionCreation();
    }

    @Test
    void serviceTokenMintFailureDoesNotInvokeDownstreamChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/genai/chatbot/help").build());
        when(anonymousSessionService.getOrCreateAnonymousSession(
                eq(null), any(ServerHttpRequest.class)))
                .thenReturn(Mono.just(SERVER_SESSION_ID));
        when(anonymousSessionProofService.issueProof(
                any(ServerHttpRequest.class), eq(SERVER_SESSION_ID)))
                .thenReturn(Mono.just(SERVER_PROOF));
        when(serviceTokenIssuer.issueAnonymousHelpToken())
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE)));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorSatisfies(error -> assertEquals(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ((ResponseStatusException) error).getStatusCode()))
                .verify();

        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    void bearerLessRequestWithOtherAuthorizationDoesNotMintServiceToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/genai/chatbot/help")
                        .header(HttpHeaders.AUTHORIZATION, "Basic ignored")
                        .header(GenAIServiceTokenIssuer.HEADER_NAME,
                                "Bearer attacker-token")
                        .build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, filteredExchange -> {
                    downstream.set(filteredExchange);
                    return Mono.empty();
                }))
                .verifyComplete();

        assertEquals("Basic ignored", downstream.get().getRequest().getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(downstream.get().getRequest().getHeaders()
                .getFirst(GenAIServiceTokenIssuer.HEADER_NAME));
        verify(serviceTokenIssuer, never()).issueAnonymousHelpToken();
        verifyNoAnonymousSessionCreation();
    }

    @Test
    void keepsAuthenticatedSessionStableAcrossRefreshRotation() {
        String firstSession = filterAuthenticatedSession(
                jwt("42", "token-id-before-refresh", "stable-family"));
        String refreshedSession = filterAuthenticatedSession(
                jwt("42", "token-id-after-refresh", "stable-family"));

        assertEquals(firstSession, refreshedSession);
    }

    @Test
    void separatesAuthenticatedSessionsAcrossLoginFamiliesAndUsers() {
        String firstFamily = filterAuthenticatedSession(
                jwt("42", "token-id-1", "family-1"));
        String secondFamily = filterAuthenticatedSession(
                jwt("42", "token-id-2", "family-2"));
        String differentUser = filterAuthenticatedSession(
                jwt("84", "token-id-3", "family-1"));

        assertNotEquals(firstFamily, secondFamily);
        assertNotEquals(firstFamily, differentUser);
    }

    @Test
    void rejectsAuthenticatedTokenWithoutSessionFamilyClaim() {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwtWithoutSessionFamily("42", "legacy-token-id"),
                List.of(new SimpleGrantedAuthority("PATIENT")));
        ServerWebExchange exchange = authenticatedExchange(
                "/api/appointment/list",
                authentication);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof IllegalStateException);
                    assertTrue(error.getMessage().contains("session family"));
                })
                .verify();

        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    void anonymousNonHelpRouteDoesNotMintSession() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/clinic/public")
                        .header("X-Session-ID", "spoofed-session")
                        .header("X-User-ID", "999")
                        .build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstream.set(filteredExchange);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertNull(downstream.get().getRequest().getHeaders().getFirst("X-Session-ID"));
        assertNull(downstream.get().getRequest().getHeaders().getFirst("X-User-ID"));
        verifyNoAnonymousSessionCreation();
    }

    @Test
    void anonymousGetHelpDoesNotMintSession() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/genai/chatbot/help")
                        .header("X-Session-ID", "spoofed-session")
                        .build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstream.set(filteredExchange);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertNull(downstream.get().getRequest().getHeaders().getFirst("X-Session-ID"));
        verifyNoAnonymousSessionCreation();
    }

    @Test
    void matrixParameterHelpPathDoesNotMintSession() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/genai/chatbot/help;v=1").build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstream.set(filteredExchange);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertNull(downstream.get().getRequest().getHeaders().getFirst("X-Session-ID"));
        verifyNoAnonymousSessionCreation();
    }

    @Test
    void deniedAnonymousIssuanceDoesNotInvokeDownstreamChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/genai/chatbot/help").build());
        when(anonymousSessionService.getOrCreateAnonymousSession(
                eq(null),
                any(ServerHttpRequest.class)))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "limit exceeded")));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorSatisfies(error -> {
                    ResponseStatusException statusException =
                            (ResponseStatusException) error;
                    assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                            statusException.getStatusCode());
                })
                .verify();

        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    void failedAnonymousIssuanceDoesNotInvokeDownstreamChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/genai/chatbot/help").build());
        when(anonymousSessionService.getOrCreateAnonymousSession(
                eq(null),
                any(ServerHttpRequest.class)))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "redis unavailable")));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorSatisfies(error -> {
                    ResponseStatusException statusException =
                            (ResponseStatusException) error;
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                            statusException.getStatusCode());
                })
                .verify();

        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    void failedAnonymousProofIssuanceDoesNotInvokeDownstreamChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/genai/chatbot/help").build());
        when(anonymousSessionService.getOrCreateAnonymousSession(
                eq(null),
                any(ServerHttpRequest.class)))
                .thenReturn(Mono.just(SERVER_SESSION_ID));
        when(anonymousSessionProofService.issueProof(
                any(ServerHttpRequest.class),
                eq(SERVER_SESSION_ID)))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "redis unavailable")));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorSatisfies(error -> {
                    ResponseStatusException statusException =
                            (ResponseStatusException) error;
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                            statusException.getStatusCode());
                })
                .verify();

        verify(chain, never()).filter(any(ServerWebExchange.class));
        assertNull(exchange.getResponse().getHeaders().getFirst(
                "X-Gateway-Anonymous-Proof"));
    }

    @Test
    void stripsIdentityHeadersWithoutCreatingSessionForAuthEndpoints() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("X-Session-ID", "spoofed-session")
                        .header("X-User-ID", "999")
                        .build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstream.set(filteredExchange);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        HttpHeaders headers = downstream.get().getRequest().getHeaders();
        assertNull(headers.getFirst("X-Session-ID"));
        assertNull(headers.getFirst("X-User-ID"));
        verifyNoAnonymousSessionCreation();
    }

    private Jwt jwt(String subject, String tokenId, String sessionFamilyId) {
        return Jwt.withTokenValue("verified-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("jti", tokenId)
                .claim("sessionFamilyId", sessionFamilyId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("email", "patient@example.com")
                .claim("roles", List.of("PATIENT", "DENTIST"))
                .claim("clinicId", 7L)
                .build();
    }

    private Jwt jwtWithoutSessionFamily(String subject, String tokenId) {
        return Jwt.withTokenValue("verified-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("jti", tokenId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("email", "patient@example.com")
                .claim("roles", List.of("PATIENT", "DENTIST"))
                .claim("clinicId", 7L)
                .build();
    }

    private String filterAuthenticatedSession(Jwt jwt) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("PATIENT")));
        ServerWebExchange exchange = authenticatedExchange(
                "/api/appointment/list",
                authentication);
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstream.set(filteredExchange);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        return downstream.get().getRequest().getHeaders().getFirst("X-Session-ID");
    }

    private ServerWebExchange authenticatedExchange(
            String path,
            JwtAuthenticationToken authentication) {
        assertTrue(authentication.isAuthenticated());
        return MockServerWebExchange.from(
                        MockServerHttpRequest.get(path)
                                .header("X-Session-ID", "caller-controlled-session")
                                .header("X-Gateway-Anonymous-Proof", "caller-controlled-proof")
                                .header("X-User-ID", "999")
                                .header("X-User-Roles", "SYSTEM_ADMIN")
                                .build())
                .mutate()
                .principal(Mono.just(authentication))
                .build();
    }

    private String expectedAuthenticatedSession(String subject, String sessionFamilyId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((subject + (char) 0x1F + sessionFamilyId)
                            .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void verifyNoAnonymousSessionCreation() {
        verify(anonymousSessionService, never()).getOrCreateAnonymousSession(
                any(),
                any(ServerHttpRequest.class));
        verify(anonymousSessionProofService, never()).issueProof(
                any(ServerHttpRequest.class),
                any());
        verify(serviceTokenIssuer, never()).issueAnonymousHelpToken();
    }
}
