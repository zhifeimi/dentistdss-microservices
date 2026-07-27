package press.mizhifei.dentist.genai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserContextServiceTest {

    private static final String SESSION_ID = "S".repeat(43);
    private static final String PROOF = "P".repeat(43);
    private static final String SOURCE_FINGERPRINT = "a".repeat(64);

    private AnonymousSessionRegistry anonymousSessionRegistry;
    private UserContextService userContextService;

    @BeforeEach
    void setUp() {
        anonymousSessionRegistry = mock(AnonymousSessionRegistry.class);
        when(anonymousSessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF))
                .thenReturn(Mono.just(
                        new AnonymousSessionRegistry.VerifiedAnonymousSession(
                                SESSION_ID,
                                SOURCE_FINGERPRINT)));
        userContextService = new UserContextService(anonymousSessionRegistry);
    }

    @Test
    void buildsAuthenticatedContextOnlyFromVerifiedJwtClaims() {
        ServerHttpRequest request = MockServerHttpRequest.get("/test")
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", "caller-controlled-proof")
                .header("X-User-ID", "forged-user")
                .header("X-User-Email", "attacker@example.com")
                .header("X-User-Roles", "SYSTEM_ADMIN")
                .header("X-Clinic-ID", "999")
                .build();

        UserContextService.UserContext context =
                userContextService.extractUserContext(request, jwt());

        assertEquals(SESSION_ID, context.getSessionId());
        assertEquals("42", context.getUserId());
        assertEquals("dentist@example.com", context.getEmail());
        assertEquals("9", context.getClinicId());
        assertNull(context.getAnonymousSourceFingerprint());
        assertTrue(context.isAuthenticated());
        assertEquals(List.of("DENTIST", "PATIENT").stream().sorted().toList(),
                context.getRoles().stream().sorted().toList());
        assertFalse(context.getRoles().contains("SYSTEM_ADMIN"));
        verify(anonymousSessionRegistry, never()).requireGatewayIssuedSession(any(), any());
    }

    @Test
    void forgedIdentityHeadersDoNotAuthenticateAnonymousHelp() {
        ServerHttpRequest request = MockServerHttpRequest.get("/test")
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header("X-User-ID", "42")
                .header("X-User-Email", "forged@example.com")
                .header("X-User-Roles", "SYSTEM_ADMIN")
                .header("X-Clinic-ID", "9")
                .build();

        StepVerifier.create(userContextService.extractAnonymousUserContext(request))
                .assertNext(context -> {
                    assertEquals(SESSION_ID, context.getSessionId());
                    assertEquals(SOURCE_FINGERPRINT,
                            context.getAnonymousSourceFingerprint());
                    assertNull(context.getUserId());
                    assertNull(context.getEmail());
                    assertNull(context.getClinicId());
                    assertFalse(context.isAuthenticated());
                    assertTrue(context.getRoles().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void doesNotGenerateFallbackForRejectedAnonymousSessionOrMissingProof() {
        when(anonymousSessionRegistry.requireGatewayIssuedSession(SESSION_ID, null))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));

        StepVerifier.create(userContextService.extractAnonymousUserContext(
                        MockServerHttpRequest.get("/test")
                                .header("X-Session-ID", SESSION_ID)
                                .build()))
                .expectErrorSatisfies(error -> {
                    ResponseStatusException statusException =
                            (ResponseStatusException) error;
                    assertEquals(HttpStatus.FORBIDDEN,
                            statusException.getStatusCode());
                })
                .verify();
    }

    @Test
    void rejectsNonNumericAuthenticatedUserBeforeProviderUse() {
        ServerHttpRequest request = MockServerHttpRequest.get("/test")
                .header("X-Session-ID", SESSION_ID)
                .build();

        assertThrows(
                IllegalStateException.class,
                () -> userContextService.extractUserContext(request, jwt("user-42")));
    }

    @Test
    void selectsHighestPriorityRole() {
        UserContextService.UserContext context = UserContextService.UserContext.builder()
                .authenticated(true)
                .roles(Arrays.asList("PATIENT", "DENTIST"))
                .build();

        assertEquals("DENTIST", userContextService.getPrimaryRole(context));
    }

    @Test
    void usesAnonymousRoleForPublicContext() {
        UserContextService.UserContext context = UserContextService.UserContext.builder()
                .authenticated(false)
                .roles(List.of())
                .build();

        assertEquals("ANONYMOUS", userContextService.getPrimaryRole(context));
    }

    @Test
    void checksAssignedRoles() {
        UserContextService.UserContext context = UserContextService.UserContext.builder()
                .roles(Arrays.asList("DENTIST", "CLINIC_ADMIN"))
                .build();

        assertTrue(userContextService.hasRole(context, "DENTIST"));
        assertTrue(userContextService.hasAnyRole(context, "PATIENT", "CLINIC_ADMIN"));
        assertFalse(userContextService.hasAnyRole(context, "PATIENT", "RECEPTIONIST"));
    }

    @Test
    void derivesDisplayNameFromVerifiedEmail() {
        UserContextService.UserContext context = UserContextService.UserContext.builder()
                .authenticated(true)
                .email("john.doe@example.com")
                .build();

        assertEquals("John doe", userContextService.getDisplayName(context));
    }

    @Test
    void usesGuestDisplayNameForAnonymousContext() {
        UserContextService.UserContext context = UserContextService.UserContext.builder()
                .authenticated(false)
                .build();

        assertEquals("Guest", userContextService.getDisplayName(context));
    }

    private Jwt jwt() {
        return jwt("42");
    }

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer("https://issuer.example")
                .audience(List.of("dentistdss-api"))
                .subject(subject)
                .issuedAt(Instant.now().minusSeconds(5))
                .notBefore(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("jti", "token-id")
                .claim("tokenType", "access")
                .claim("email", "dentist@example.com")
                .claim("roles", List.of("DENTIST", "PATIENT"))
                .claim("clinicId", 9L)
                .build();
    }
}
