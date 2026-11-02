package press.mizhifei.dentist.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import press.mizhifei.dentist.gateway.service.AnonymousSessionProofService;
import press.mizhifei.dentist.gateway.service.AnonymousSessionService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gateway side of {@code GET /api/public/clinics}: anonymous access,
 * routing to clinic-service's {@code /public/clinics}, per-IP rate limiting,
 * and passing clinic-service's caching headers through untouched. A stub
 * global filter stands in for clinic-service at the end of the chain.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "eureka.client.enabled=false",
                "management.health.redis.enabled=false",
                "app.security.anonymous-session-issuance.fingerprint-key=test-fingerprint-key-with-at-least-32-bytes",
                "SPRING_CONFIG_USER=test",
                "SPRING_CONFIG_PASS=test"
        })
@Import(PublicDirectoryGatewayIntegrationTest.StubBackendConfiguration.class)
class PublicDirectoryGatewayIntegrationTest {

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
    private StubClinicService stubClinicService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        stubClinicService.reset();
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/public/clinics", "/api/%70ublic/clinics",
            "/%61pi/public/clinics", "/%61pi/%70ublic/clinics"
    })
    @SuppressWarnings({"rawtypes", "unchecked"})
    void anonymousDirectoryRequestIsRoutedWithItsCachingHeadersIntact(String path) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.just(0L));

        webTestClient.get()
                .uri(URI.create("http://localhost:" + port + path))
                .exchange()
                .expectStatus().isOk()
                // Spring Security must not replace clinic-service's cache policy.
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=300, public")
                .expectHeader().valueEquals(HttpHeaders.ETAG, "W/\"abc\"")
                .expectHeader().doesNotExist(HttpHeaders.PRAGMA)
                .expectBody(String.class).isEqualTo("{\"generatedAt\":\"x\",\"clinics\":[]}");

        assertEquals(1, stubClinicService.invocations.get());
        assertEquals("clinic-service-public-directory", stubClinicService.routeId.get());
        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/public/clinics/smile-dental-parramatta",
            "/api/%70ublic/clinics/smile-dental-parramatta"
    })
    @SuppressWarnings({"rawtypes", "unchecked"})
    void singleClinicRouteIsAnonymousToo(String path) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.just(0L));

        webTestClient.get()
                .uri(URI.create("http://localhost:" + port + path))
                .exchange()
                .expectStatus().isOk();

        assertEquals("clinic-service-public-directory", stubClinicService.routeId.get());
        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/public/clinics", "/api/%70ublic/clinics",
            "/%61pi/public/clinics", "/%61pi/%70ublic/clinics"
    })
    @SuppressWarnings({"rawtypes", "unchecked"})
    void fullWindowAnswersTooManyRequestsWithoutReachingClinicService(String path) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.just(1_200L));

        webTestClient.get()
                .uri(URI.create("http://localhost:" + port + path))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(String.class).isEqualTo("{\"success\":false,\"message\":\"Too many requests\"}");

        assertEquals(0, stubClinicService.invocations.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/public/clinics", "/api/%70ublic/clinics",
            "/%61pi/public/clinics", "/%61pi/%70ublic/clinics"
    })
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unavailableRateLimitStateFailsClosed(String path) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("down")));

        webTestClient.get()
                .uri(URI.create("http://localhost:" + port + path))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertEquals(0, stubClinicService.invocations.get());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void directoryIsReadOnly() {
        webTestClient.post()
                .uri("/api/public/clinics")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get()
                .uri("/api/public/clinics/smile-dental/anything")
                .exchange()
                .expectStatus().isUnauthorized();

        assertEquals(0, stubClinicService.invocations.get());
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @TestConfiguration
    static class StubBackendConfiguration {

        @Bean
        StubClinicService stubClinicService() {
            return new StubClinicService();
        }
    }

    /** Answers routed public-directory requests the way clinic-service does. */
    static final class StubClinicService implements GlobalFilter, Ordered {

        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<String> routeId = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route == null || !route.getId().equals("clinic-service-public-directory")) {
                return chain.filter(exchange);
            }
            invocations.incrementAndGet();
            routeId.set(route.getId());
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.setCacheControl("max-age=300, public");
            headers.setETag("W/\"abc\"");
            headers.setContentType(MediaType.APPLICATION_JSON);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
                    .wrap("{\"generatedAt\":\"x\",\"clinics\":[]}".getBytes(StandardCharsets.UTF_8))));
        }

        /** After the rate limiter, before the load balancer would forward the request. */
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 100;
        }

        void reset() {
            invocations.set(0);
            routeId.set(null);
        }
    }
}
