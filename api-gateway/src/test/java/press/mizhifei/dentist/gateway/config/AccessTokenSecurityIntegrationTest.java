package press.mizhifei.dentist.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import press.mizhifei.dentist.gateway.service.AnonymousSessionProofService;
import press.mizhifei.dentist.gateway.service.AnonymousSessionService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "eureka.client.enabled=false",
                "management.health.redis.enabled=false",
                "app.security.access-token-state.timeout=PT0.05S",
                "app.security.anonymous-session-issuance.fingerprint-key=test-fingerprint-key-with-at-least-32-bytes",
                "SPRING_CONFIG_USER=test",
                "SPRING_CONFIG_PASS=test"
        })
@Import(AccessTokenSecurityIntegrationTest.CaptureConfiguration.class)
class AccessTokenSecurityIntegrationTest {

    private static final String BEARER_TOKEN = "accepted-bearer-token";
    private static final String ALLOWED_ORIGIN = "https://dentistdss.vercel.app";

    @LocalServerPort
    private int port;

    @MockitoBean(name = "localJwtDecoder")
    private ReactiveJwtDecoder localJwtDecoder;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @MockitoBean
    private AnonymousSessionService anonymousSessionService;

    @MockitoBean
    private AnonymousSessionProofService anonymousSessionProofService;

    @Autowired
    private RequestCaptureGlobalFilter requestCaptureGlobalFilter;

    @Autowired
    private SearchBackendGlobalFilter searchBackendGlobalFilter;

    private ReactiveValueOperations<String, String> valueOperations;
    private WebTestClient webTestClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        valueOperations = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        requestCaptureGlobalFilter.reset();
        searchBackendGlobalFilter.reset();
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void revokedFamilyReturnsRfc6750InvalidTokenWithoutPropagatingTrustedHeaders() {
        when(localJwtDecoder.decode(BEARER_TOKEN)).thenReturn(Mono.just(jwt(7L)));
        when(valueOperations.multiGet(anyCollection()))
                .thenReturn(Mono.just(List.of("7:1", "revoked")));

        webTestClient.get()
                .uri("/api/appointment/list")
                .headers(headers -> {
                    headers.setBearerAuth(BEARER_TOKEN);
                    headers.set("X-Session-ID", "attacker-session");
                    headers.set("X-User-ID", "999");
                    headers.set("X-Clinic-ID", "999");
                })
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        value -> assertTrue(value.contains("error=\"invalid_token\"")))
                .expectHeader().doesNotExist("X-Session-ID");

        assertEquals(0, requestCaptureGlobalFilter.invocationCount());
        assertNull(requestCaptureGlobalFilter.lastHeaders());
    }

    @Test
    void redisFailureOnProtectedAuthRouteReturnsSanitizedServiceUnavailable() {
        when(localJwtDecoder.decode(BEARER_TOKEN)).thenReturn(Mono.just(jwt(7L)));
        when(valueOperations.multiGet(anyCollection()))
                .thenReturn(Mono.error(new IllegalStateException("redis detail must not leak")));

        webTestClient.get()
                .uri("/api/auth/me")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .headers(headers -> {
                    headers.setBearerAuth(BEARER_TOKEN);
                    headers.set("X-Session-ID", "attacker-session");
                    headers.set("X-User-ID", "999");
                })
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        ALLOWED_ORIGIN)
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectHeader().doesNotExist("X-Session-ID")
                .expectBody()
                .jsonPath("$.code").isEqualTo("SECURITY_STATE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo(
                        "Authentication is temporarily unavailable")
                .jsonPath("$.message").value(
                        value -> assertNotEquals("redis detail must not leak", value));

        assertEquals(0, requestCaptureGlobalFilter.invocationCount());
        assertNull(requestCaptureGlobalFilter.lastHeaders());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/clinic/search", "/api/clinic-admin/clinics/search",
            "/%61pi/clinic/search", "/api/clinic/%73earch",
            "/%61pi/clinic-admin/clinics/search", "/api/clinic-admin/clinics/%73earch"
    })
    @SuppressWarnings({"rawtypes", "unchecked"})
    void anonymousClinicSearchDoesNotReadAccessTokenState(String path) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.just(0L));

        webTestClient.post()
                .uri(URI.create("http://localhost:" + port + path))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"keywords\":\"Sydney\"}")
                .headers(headers -> {
                    headers.set("X-Session-ID", "attacker-session");
                    headers.set("X-User-ID", "999");
                    headers.set("X-Clinic-ID", "999");
                })
                .exchange()
                .expectStatus().isOk();

        verifyNoInteractions(localJwtDecoder);
        verify(redisTemplate, never()).opsForValue();
        assertEquals(1, requestCaptureGlobalFilter.invocationCount());
        HttpHeaders headers = requestCaptureGlobalFilter.lastHeaders();
        assertNull(headers.getFirst("X-Session-ID"));
        assertNull(headers.getFirst("X-User-ID"));
        assertNull(headers.getFirst("X-Clinic-ID"));
        assertEquals("/clinic/search", searchBackendGlobalFilter.lastPath());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/clinic/search", "/api/clinic-admin/clinics/search",
            "/%61pi/clinic/search", "/api/clinic/%73earch",
            "/%61pi/clinic-admin/clinics/search", "/api/clinic-admin/clinics/%73earch"
    })
    @SuppressWarnings({"rawtypes", "unchecked"})
    void exhaustedSearchQuotaBlocksBeforeTheBackend(String path) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.just(1_200L));

        webTestClient.post()
                .uri(URI.create("http://localhost:" + port + path))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"keywords\":\"Sydney\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");

        assertNull(searchBackendGlobalFilter.lastPath());
        verifyNoInteractions(localJwtDecoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/clinic/search", "/api/clinic-admin/clinics/search"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void searchRateLimitFailsClosedWhenRedisIsUnavailable(String path) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("down")));

        webTestClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"keywords\":\"Sydney\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertNull(searchBackendGlobalFilter.lastPath());
        verifyNoInteractions(localJwtDecoder);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bothSearchPathsConsumeTheSameClientQuota() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.just(0L), Flux.just(1_200L));

        webTestClient.post().uri("/api/clinic/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"keywords\":\"Sydney\"}")
                .exchange().expectStatus().isOk();
        searchBackendGlobalFilter.reset();
        webTestClient.post().uri("/api/clinic-admin/clinics/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"keywords\":\"Sydney\"}")
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertNull(searchBackendGlobalFilter.lastPath());
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(2)).execute(any(DefaultRedisScript.class), keys.capture(), any(Object[].class));
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/clinic/search", "PUT, /api/clinic/search",
            "GET, /api/clinic-admin/clinics/search", "PUT, /api/clinic-admin/clinics/search",
            "POST, /api/clinic-admin/clinics/search/other", "POST, /api/clinic-admin/clinics",
            "POST, /api/clinic/search/other"
    })
    void onlyTheExactSearchPostPathsAreAnonymous(String method, String path) {
        webTestClient.method(HttpMethod.valueOf(method)).uri(path)
                .exchange().expectStatus().isUnauthorized();

        assertEquals(0, requestCaptureGlobalFilter.invocationCount());
        verifyNoInteractions(redisTemplate, localJwtDecoder);
    }

    @Test
    void malformedSecurityVersionFailsBeforeRedisOrTrustedHeaderPropagation() {
        when(localJwtDecoder.decode(BEARER_TOKEN)).thenReturn(Mono.just(jwt(0L)));

        webTestClient.get()
                .uri("/api/appointment/list")
                .headers(headers -> {
                    headers.setBearerAuth(BEARER_TOKEN);
                    headers.set("X-Session-ID", "attacker-session");
                    headers.set("X-User-ID", "999");
                })
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        value -> assertTrue(value.contains("error=\"invalid_token\"")));

        verify(redisTemplate, never()).opsForValue();
        assertEquals(0, requestCaptureGlobalFilter.invocationCount());
    }

    @Test
    void trustedHeadersAreDerivedOnlyAfterSuccessfulRedisValidation() {
        when(localJwtDecoder.decode(BEARER_TOKEN)).thenReturn(Mono.just(jwt(7L)));
        when(valueOperations.multiGet(anyCollection()))
                .thenReturn(Mono.just(List.of("7:1", "active")));

        webTestClient.get()
                .uri("/api/appointment/list")
                .headers(headers -> {
                    headers.setBearerAuth(BEARER_TOKEN);
                    headers.set("X-Session-ID", "attacker-session");
                    headers.set("X-User-ID", "999");
                    headers.set("X-Clinic-ID", "999");
                })
                .exchange()
                .expectStatus().is5xxServerError();

        assertEquals(1, requestCaptureGlobalFilter.invocationCount());
        HttpHeaders headers = requestCaptureGlobalFilter.lastHeaders();
        assertEquals("42", headers.getFirst("X-User-ID"));
        assertEquals("patient@example.com", headers.getFirst("X-User-Email"));
        assertEquals("PATIENT", headers.getFirst("X-User-Roles"));
        assertEquals("7", headers.getFirst("X-Clinic-ID"));
        assertTrue(StringUtils.hasText(headers.getFirst("X-Session-ID")));
        assertNotEquals("attacker-session", headers.getFirst("X-Session-ID"));
    }

    private Jwt jwt(Object securityVersion) {
        return Jwt.withTokenValue(BEARER_TOKEN)
                .header("alg", "RS256")
                .issuer("https://api.mizhifei.press")
                .audience(List.of("dentistdss-api"))
                .subject("42")
                .claim("jti", "token-id")
                .claim("tokenType", "access")
                .claim("securityVersion", securityVersion)
                .claim("sessionFamilyId", "family-1")
                .claim("email", "patient@example.com")
                .claim("roles", List.of("PATIENT"))
                .claim("clinicId", 7L)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfiguration {

        @Bean
        RequestCaptureGlobalFilter requestCaptureGlobalFilter() {
            return new RequestCaptureGlobalFilter();
        }

        @Bean
        SearchBackendGlobalFilter searchBackendGlobalFilter() {
            return new SearchBackendGlobalFilter();
        }
    }

    /** Stands in for clinic-service after the route's path filters have run. */
    static final class SearchBackendGlobalFilter implements GlobalFilter, Ordered {

        private final AtomicReference<String> lastPath = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route == null || !(route.getId().equals("clinic-service")
                    || route.getId().equals("clinic-service-search-alias"))) {
                return chain.filter(exchange);
            }
            lastPath.set(exchange.getRequest().getURI().getPath());
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        }

        @Override
        public int getOrder() {
            return 2;
        }

        void reset() {
            lastPath.set(null);
        }

        String lastPath() {
            return lastPath.get();
        }
    }

    static final class RequestCaptureGlobalFilter implements GlobalFilter, Ordered {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private final AtomicReference<HttpHeaders> lastHeaders = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            invocationCount.incrementAndGet();
            lastHeaders.set(HttpHeaders.readOnlyHttpHeaders(
                    new HttpHeaders(exchange.getRequest().getHeaders())));
            return chain.filter(exchange);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 1;
        }

        void reset() {
            invocationCount.set(0);
            lastHeaders.set(null);
        }

        int invocationCount() {
            return invocationCount.get();
        }

        HttpHeaders lastHeaders() {
            return lastHeaders.get();
        }
    }
}
