package press.mizhifei.dentist.security.servicetoken;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import press.mizhifei.dentist.security.ReactiveBearerTokenFailureHandler;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTokenWebFiltersTest {

    private static RSAKey callerKey;
    private static AuthenticationWebFilter filter;

    @BeforeAll
    static void setUp() throws Exception {
        callerKey = ServiceTokenTestKeys.generateKey(ServiceTokenTestKeys.APPOINTMENT_KID);
        ServiceTokenReactiveJwtDecoder decoder = new ServiceTokenReactiveJwtDecoder(
                List.of(new TrustedServiceKey(
                        ServiceTokenTestKeys.APPOINTMENT_KID,
                        ServiceTokenTestKeys.publicKeyPem(callerKey),
                        "appointment-service",
                        java.util.Set.of("notification:send"))),
                "notification-service");
        filter = ServiceTokenWebFilters.reactiveAuthenticationFilter(
                decoder,
                ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/notification/send"),
                new ReactiveBearerTokenFailureHandler());
    }

    @Test
    void authenticatesConfiguredPathsWithAValidServiceCredential() throws Exception {
        String token = ServiceTokenTestKeys.validToken(
                callerKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                "appointment-service", "notification:send");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/notification/send")
                        .header(ServiceTokenConstants.HEADER_NAME, "Bearer " + token));

        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.filter(exchange, chain(seen, new AtomicBoolean(true))).block();

        assertTrue(seen.get() instanceof org.springframework.security.oauth2.server.resource
                .authentication.JwtAuthenticationToken);
        assertEquals("appointment-service", seen.get().getName());
        assertTrue(seen.get().getAuthorities().stream()
                .anyMatch(a -> "SERVICE_NOTIFICATION_SEND".equals(a.getAuthority())));
    }

    @Test
    void passesThroughWhenNoServiceHeaderIsPresent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/notification/send"));

        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicBoolean chainCalled = new AtomicBoolean();
        filter.filter(exchange, chain(seen, chainCalled)).block();

        assertTrue(chainCalled.get());
        assertNull(seen.get(), "no authentication without a service header");
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void neverActivatesWhenAUserAuthorizationHeaderIsPresent() throws Exception {
        String token = ServiceTokenTestKeys.validToken(
                callerKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                "appointment-service", "notification:send");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/notification/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .header(ServiceTokenConstants.HEADER_NAME, "Bearer " + token));

        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.filter(exchange, chain(seen, new AtomicBoolean(true))).block();
        assertNull(seen.get(), "user JWT path stays in charge when Authorization is present");
    }

    @Test
    void ignoresUnmatchedPaths() throws Exception {
        String token = ServiceTokenTestKeys.validToken(
                callerKey, ServiceTokenTestKeys.APPOINTMENT_KID,
                "appointment-service", "notification:send");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/notification/user/42")
                        .header(ServiceTokenConstants.HEADER_NAME, "Bearer " + token));

        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.filter(exchange, chain(seen, new AtomicBoolean(true))).block();
        assertNull(seen.get());
    }

    @Test
    void rejectsForgedCredentialWithBearerError() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/notification/send")
                        .header(ServiceTokenConstants.HEADER_NAME, "Bearer forged.jwt.token"));

        AtomicBoolean chainCalled = new AtomicBoolean();
        filter.filter(exchange, chain(new AtomicReference<>(), chainCalled)).block();

        assertFalse(chainCalled.get());
        assertEquals(401, exchange.getResponse().getStatusCode().value());
    }

    private static org.springframework.web.server.WebFilterChain chain(
            AtomicReference<Authentication> seen,
            AtomicBoolean chainCalled) {
        return ex -> ReactiveSecurityContextHolder.getContext()
                .doOnNext(context -> seen.set(context.getAuthentication()))
                .then()
                .doOnTerminate(() -> chainCalled.set(true));
    }
}
