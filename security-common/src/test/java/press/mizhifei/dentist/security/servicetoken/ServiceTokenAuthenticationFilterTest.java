package press.mizhifei.dentist.security.servicetoken;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import press.mizhifei.dentist.security.ServletBearerTokenFailureHandler;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTokenAuthenticationFilterTest {

    private static final String AUDIENCE = "user-profile-service";

    private static RSAKey notificationTrustedKey;
    private static ServiceTokenAuthenticationFilter filter;

    @BeforeAll
    static void setUp() throws Exception {
        notificationTrustedKey = ServiceTokenTestKeys.generateKey(ServiceTokenTestKeys.AUTH_KID);
        ServiceTokenJwtDecoder decoder = new ServiceTokenJwtDecoder(
                List.of(new TrustedServiceKey(
                        ServiceTokenTestKeys.AUTH_KID,
                        ServiceTokenTestKeys.publicKeyPem(notificationTrustedKey),
                        "notification-service",
                        java.util.Set.of("user:contact:read"))),
                AUDIENCE);
        RequestMatcher contactReads = new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/user/*/email"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/user/*/name"));
        filter = new ServiceTokenAuthenticationFilter(
                decoder, contactReads, new ServletBearerTokenFailureHandler());
    }

    @Test
    void authenticatesConfiguredPathsWithAValidServiceCredential() throws Exception {
        String token = ServiceTokenTestKeys.validToken(
                notificationTrustedKey, ServiceTokenTestKeys.AUTH_KID,
                "notification-service", AUDIENCE, "user:contact:read");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/42/email");
        request.addHeader(ServiceTokenConstants.HEADER_NAME, "Bearer " + token);

        AtomicReference<Authentication> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                seen.set(SecurityContextHolder.getContext().getAuthentication()));

        assertEquals(200, response.getStatus());
        assertTrue(seen.get() instanceof JwtAuthenticationToken,
                "downstream sees a service authentication");
        assertEquals("notification-service", seen.get().getName());
        assertTrue(seen.get().getAuthorities().stream()
                .anyMatch(a -> "SERVICE_USER_CONTACT_READ".equals(a.getAuthority())));
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "stateless filter clears the context afterwards");
    }

    @Test
    void passesThroughWhenNoServiceHeaderIsPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/42/email");
        AtomicBoolean chainCalled = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            chainCalled.set(true);
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        });
        assertTrue(chainCalled.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void neverActivatesWhenAUserAuthorizationHeaderIsPresent() throws Exception {
        String token = ServiceTokenTestKeys.validToken(
                notificationTrustedKey, ServiceTokenTestKeys.AUTH_KID,
                "notification-service", AUDIENCE, "user:contact:read");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/42/email");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer user-token");
        request.addHeader(ServiceTokenConstants.HEADER_NAME, "Bearer " + token);

        AtomicBoolean chainCalled = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            chainCalled.set(true);
            assertNull(SecurityContextHolder.getContext().getAuthentication(),
                    "user JWT path stays in charge when Authorization is present");
        });
        assertTrue(chainCalled.get());
    }

    @Test
    void ignoresUnmatchedPaths() throws Exception {
        String token = ServiceTokenTestKeys.validToken(
                notificationTrustedKey, ServiceTokenTestKeys.AUTH_KID,
                "notification-service", AUDIENCE, "user:contact:read");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/list/all");
        request.addHeader(ServiceTokenConstants.HEADER_NAME, "Bearer " + token);

        AtomicBoolean chainCalled = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            chainCalled.set(true);
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        });
        assertTrue(chainCalled.get());
    }

    @Test
    void rejectsForgedCredentialWithSanitizedBearerError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/42/email");
        request.addHeader(ServiceTokenConstants.HEADER_NAME, "Bearer forged.jwt.token");

        AtomicBoolean chainCalled = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertFalse(chainCalled.get());
        assertEquals(401, response.getStatus());
    }
}
