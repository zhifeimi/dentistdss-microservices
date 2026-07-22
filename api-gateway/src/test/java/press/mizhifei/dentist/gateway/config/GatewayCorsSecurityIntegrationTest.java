package press.mizhifei.dentist.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import press.mizhifei.dentist.gateway.security.GenAIServiceTokenIssuer;
import press.mizhifei.dentist.gateway.service.AnonymousSessionProofService;
import press.mizhifei.dentist.gateway.service.AnonymousSessionService;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
class GatewayCorsSecurityIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    private static final String SERVER_SESSION_ID = "S".repeat(43);
    private static final String SERVER_PROOF = "P".repeat(43);

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
        when(anonymousSessionService.getOrCreateAnonymousSession(any(), any()))
                .thenReturn(Mono.just(SERVER_SESSION_ID));
        when(anonymousSessionProofService.issueProof(any(), any()))
                .thenReturn(Mono.just(SERVER_PROOF));
        when(serviceTokenIssuer.issueAnonymousHelpToken())
                .thenReturn(Mono.just("service-token"));
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void unauthorizedResponseIncludesCorsHeaders() {
        webTestClient.get()
                .uri("/api/appointment/list")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    @Test
    void preflightUsesGatewayCorsPolicyBeforeAuthorization() {
        webTestClient.options()
                .uri("/api/appointment/list")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        value -> org.junit.jupiter.api.Assertions.assertTrue(value.contains(HttpMethod.GET.name())))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> org.junit.jupiter.api.Assertions.assertTrue(
                                value.toLowerCase().contains(HttpHeaders.AUTHORIZATION.toLowerCase())));
    }

    @Test
    void allowsOnlyExactAnonymousPostForGenAiHelp() {
        webTestClient.post()
                .uri("/api/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("help")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().valueEquals("X-Session-ID", SERVER_SESSION_ID)
                .expectHeader().doesNotExist("X-Gateway-Anonymous-Proof");

        webTestClient.get()
                .uri("/api/genai/chatbot/help")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.post()
                .uri("/api/genai/chatbot/help/extra")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("help")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.post()
                .uri("/api/genai/chatbot/help;v=1")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("help")
                .exchange()
                .expectStatus().is4xxClientError()
                .expectHeader().doesNotExist("X-Session-ID");

        webTestClient.post()
                .uri("/api/genai/chatbot/triage")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("symptoms")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsInvalidBearerTokenForPublicGenAiHelp() {
        webTestClient.post()
                .uri("/api/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .headers(headers -> headers.setBearerAuth("invalid"))
                .bodyValue("help")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void disallowedOriginReceivesNoCorsPermission() {
        webTestClient.get()
                .uri("/api/appointment/list")
                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
