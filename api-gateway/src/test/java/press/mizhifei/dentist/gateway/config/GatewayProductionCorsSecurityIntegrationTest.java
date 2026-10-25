package press.mizhifei.dentist.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import press.mizhifei.dentist.gateway.security.GenAIServiceTokenIssuer;
import press.mizhifei.dentist.gateway.service.AnonymousSessionProofService;
import press.mizhifei.dentist.gateway.service.AnonymousSessionService;

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
@ActiveProfiles("prod")
class GatewayProductionCorsSecurityIntegrationTest {

    private static final String NEW_ACCESS_POINT = "https://dentist.zhifei.org";

    @LocalServerPort
    private int port;

    @MockitoBean
    private AnonymousSessionService anonymousSessionService;

    @MockitoBean
    private AnonymousSessionProofService anonymousSessionProofService;

    @MockitoBean
    private GenAIServiceTokenIssuer serviceTokenIssuer;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void allowsNewAccessPointPreflight() {
        webTestClient.options()
                .uri("/api/auth/me")
                .header(HttpHeaders.ORIGIN, NEW_ACCESS_POINT)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, NEW_ACCESS_POINT)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        value -> org.junit.jupiter.api.Assertions.assertTrue(value.contains(HttpMethod.GET.name())))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> org.junit.jupiter.api.Assertions.assertTrue(
                                value.toLowerCase().contains(HttpHeaders.AUTHORIZATION.toLowerCase())));
    }
}
