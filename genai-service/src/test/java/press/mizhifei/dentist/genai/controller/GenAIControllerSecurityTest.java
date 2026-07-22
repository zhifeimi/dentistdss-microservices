package press.mizhifei.dentist.genai.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import press.mizhifei.dentist.genai.config.GenAISecurityConfig;
import press.mizhifei.dentist.genai.model.AIInteraction;
import press.mizhifei.dentist.genai.model.Conversation;
import press.mizhifei.dentist.genai.model.InteractionStatus;
import press.mizhifei.dentist.genai.security.GenAIServiceJwtDecoder;
import press.mizhifei.dentist.genai.service.AnonymousSessionRegistry;
import press.mizhifei.dentist.genai.service.ChatService;
import press.mizhifei.dentist.genai.service.ConversationPersistenceService;
import press.mizhifei.dentist.genai.service.GenAIInteractionService;
import press.mizhifei.dentist.genai.service.GenAIPromptValidator;
import press.mizhifei.dentist.genai.service.TokenRateLimiter;
import press.mizhifei.dentist.genai.service.UserContextService;
import press.mizhifei.dentist.security.ReactiveJwtSecurityAutoConfiguration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@WebFluxTest(GenAIController.class)
@ImportAutoConfiguration(ReactiveWebSecurityAutoConfiguration.class)
@Import({
        GenAISecurityConfig.class,
        ReactiveJwtSecurityAutoConfiguration.class,
        GenAIPromptValidator.class,
        UserContextService.class
})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.example/jwks",
        "jwt.issuer=https://issuer.example",
        "jwt.audience=dentistdss-api",
        "springdoc.api-docs.enabled=false",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class GenAIControllerSecurityTest {

    private static final String SESSION_ID = "S".repeat(43);
    private static final String PROOF = "P".repeat(43);
    private static final String SECOND_SESSION_ID = "T".repeat(43);
    private static final String SECOND_PROOF = "Q".repeat(43);
    private static final String SOURCE_FINGERPRINT = "a".repeat(64);
    private static final String ANONYMOUS_RATE_LIMIT_KEY =
            "source:" + SOURCE_FINGERPRINT;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private GenAIController genAIController;

    private WebTestClient webTestClient;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private TokenRateLimiter tokenRateLimiter;

    @MockitoBean
    private ConversationPersistenceService conversationPersistenceService;

    @MockitoBean
    private GenAIInteractionService interactionService;

    @MockitoBean
    private AnonymousSessionRegistry anonymousSessionRegistry;

    @MockitoBean(name = "dentistDssAccessTokenReactiveJwtDecoder")
    private ReactiveJwtDecoder reactiveJwtDecoder;

    @MockitoBean
    private GenAIServiceJwtDecoder serviceJwtDecoder;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
        when(tokenRateLimiter.tryConsume(anyString(), anyLong())).thenReturn(Mono.just(false));
        when(tokenRateLimiter.tryAcquireStream(anyString())).thenReturn(Mono.just(true));
        when(tokenRateLimiter.releaseStream(anyString())).thenReturn(Mono.empty());
        when(anonymousSessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF))
                .thenReturn(Mono.just(verifiedSession(SESSION_ID)));
        when(anonymousSessionRegistry.requireGatewayIssuedSession(
                SECOND_SESSION_ID, SECOND_PROOF))
                .thenReturn(Mono.just(verifiedSession(SECOND_SESSION_ID)));
        when(reactiveJwtDecoder.decode("invalid"))
                .thenReturn(Mono.error(new BadJwtException("invalid token")));
        when(serviceJwtDecoder.decode("service-token"))
                .thenReturn(Mono.just(serviceJwt()));
        when(conversationPersistenceService.openConversation(
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                anyString()))
                .thenAnswer(invocation -> Mono.just(openConversation(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(3))));
        when(conversationPersistenceService.appendAssistantMessage(
                any(ConversationPersistenceService.OpenConversation.class),
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                any(InteractionStatus.class)))
                .thenAnswer(invocation -> Mono.just(
                        ((ConversationPersistenceService.OpenConversation)
                                invocation.getArgument(0)).conversation()));
        when(interactionService.startInteraction(
                anyString(),
                anyString(),
                any(ConversationPersistenceService.OpenConversation.class),
                any(),
                any(UserContextService.UserContext.class),
                any(Instant.class)))
                .thenReturn(Mono.just(AIInteraction.builder()
                        .id("interaction-1")
                        .status(InteractionStatus.IN_PROGRESS)
                        .build()));
        when(interactionService.completeInteraction(
                any(AIInteraction.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(Mono.empty());
        when(interactionService.failInteraction(
                any(AIInteraction.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(Mono.empty());
        when(interactionService.cancelInteraction(
                any(AIInteraction.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(Mono.empty());
    }

    @Test
    void allowsAnonymousHelpButDoesNotUseForgedIdentityForRateLimit() {
        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .header("X-User-ID", "42")
                .header("X-User-Roles", "SYSTEM_ADMIN")
                .header("X-Clinic-ID", "999")
                .bodyValue("How do I book an appointment?")
                .exchange()
                .expectStatus().isOk();

        verify(tokenRateLimiter).tryConsume(eq(ANONYMOUS_RATE_LIMIT_KEY), anyLong());
    }

    @Test
    void differentSessionsFromSameSourceShareQuotaAndConcurrencyIdentity() {
        StepVerifier.create(genAIController.help(
                        "first",
                        anonymousRequest(SESSION_ID, PROOF),
                        null))
                .expectNextMatches(message -> message.contains("maximal inquiries"))
                .verifyComplete();
        StepVerifier.create(genAIController.help(
                        "second",
                        anonymousRequest(SECOND_SESSION_ID, SECOND_PROOF),
                        null))
                .expectNextMatches(message -> message.contains("maximal inquiries"))
                .verifyComplete();

        verify(tokenRateLimiter, times(2)).tryConsume(
                eq(ANONYMOUS_RATE_LIMIT_KEY),
                anyLong());
        verify(tokenRateLimiter, never()).tryConsume(
                eq("session:" + SESSION_ID),
                anyLong());
        verify(tokenRateLimiter, never()).tryConsume(
                eq("session:" + SECOND_SESSION_ID),
                anyLong());
    }

    @Test
    void rejectsAnonymousHelpWhenSessionWasNotIssuedByGateway() {
        when(anonymousSessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));

        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .bodyValue("help")
                .exchange()
                .expectStatus().isForbidden();

        verify(tokenRateLimiter, never()).tryConsume(anyString(), anyLong());
        verify(chatService, never()).streamChatWithContext(
                anyString(), anyString(), anyList(), any(), any());
    }

    @Test
    void rejectsAnonymousHelpWithoutGatewayProofBeforeQuotaUse() {
        when(anonymousSessionRegistry.requireGatewayIssuedSession(SESSION_ID, null))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));

        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .bodyValue("help")
                .exchange()
                .expectStatus().isForbidden();

        verify(tokenRateLimiter, never()).tryConsume(anyString(), anyLong());
        verify(chatService, never()).streamChatWithContext(
                anyString(), anyString(), anyList(), any(), any());
    }

    @Test
    void rejectsReplayedGatewayProofBeforeSecondQuotaUse() {
        when(anonymousSessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF))
                .thenReturn(
                        Mono.just(verifiedSession(SESSION_ID)),
                        Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));

        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .bodyValue("first")
                .exchange()
                .expectStatus().isOk();
        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .bodyValue("replay")
                .exchange()
                .expectStatus().isForbidden();

        verify(tokenRateLimiter).tryConsume(
                eq(ANONYMOUS_RATE_LIMIT_KEY),
                anyLong());
    }

    @Test
    void returnsServiceUnavailableWhenAnonymousSessionRegistryFails() {
        when(anonymousSessionRegistry.requireGatewayIssuedSession(SESSION_ID, PROOF))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE)));

        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .bodyValue("help")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(tokenRateLimiter, never()).tryConsume(anyString(), anyLong());
    }

    @Test
    void rejectsBlankAnonymousPromptBeforeQuotaOrPersistence() {
        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .bodyValue(" \n\t ")
                .exchange()
                .expectStatus().isBadRequest();

        verify(tokenRateLimiter, never()).tryConsume(anyString(), anyLong());
        verify(conversationPersistenceService, never()).openConversation(
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                anyString());
        verify(chatService, never()).streamChatWithContext(
                anyString(), anyString(), anyList(), any(), any());
    }

    @Test
    void rejectsOversizedAuthenticatedPromptBeforeQuotaOrPersistence() {
        jwtClient("42", 9L, "PATIENT")
                .post()
                .uri("/genai/chatbot/triage")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .bodyValue("x".repeat(8001))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE);

        verify(tokenRateLimiter, never()).tryConsume(anyString(), anyLong());
        verify(conversationPersistenceService, never()).openConversation(
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                anyString());
        verify(chatService, never()).streamChatWithContext(
                anyString(), anyString(), anyList(), any(), any());
    }

    @Test
    void tokenQuotaDenialPreventsStreamAcquisitionAndProviderWork() {
        var request = MockServerHttpRequest.post("/genai/chatbot/help")
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .build();

        StepVerifier.create(genAIController.help("help", request, null))
                .expectNextMatches(message -> message.contains("maximal inquiries"))
                .verifyComplete();

        verify(tokenRateLimiter, never()).tryAcquireStream(anyString());
        verify(tokenRateLimiter, never()).releaseStream(anyString());
        verify(conversationPersistenceService, never()).openConversation(
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                anyString());
        verify(chatService, never()).streamChatWithContext(
                anyString(), anyString(), anyList(), any(), any());
    }

    @Test
    void concurrentStreamDenialPreventsConversationAndProviderWork() {
        when(tokenRateLimiter.tryConsume(anyString(), anyLong())).thenReturn(Mono.just(true));
        when(tokenRateLimiter.tryAcquireStream(anyString())).thenReturn(Mono.just(false));
        var request = MockServerHttpRequest.post("/genai/chatbot/help")
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .build();

        StepVerifier.create(genAIController.help("help", request, null))
                .expectNextMatches(message -> message.contains("maximum number of active"))
                .verifyComplete();

        verify(conversationPersistenceService, never()).openConversation(
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                anyString());
        verify(chatService, never()).streamChatWithContext(
                anyString(), anyString(), anyList(), any(), any());
        verify(tokenRateLimiter, never()).releaseStream(anyString());
    }

    @Test
    void quotaStoreFailureReturnsServiceUnavailableBeforeProviderWork() {
        when(tokenRateLimiter.tryConsume(anyString(), anyLong()))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE)));

        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .bodyValue("help")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(tokenRateLimiter, never()).tryAcquireStream(anyString());
        verify(conversationPersistenceService, never()).openConversation(
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                anyString());
        verify(chatService, never()).streamChatWithContext(
                anyString(), anyString(), anyList(), any(), any());
    }

    @Test
    void persistsCompletedOutcomeAndReleasesConcurrentStreamLease() {
        prepareAnonymousStream(Flux.just("answer"));

        StepVerifier.create(anonymousHelpStream())
                .expectNext("answer")
                .verifyComplete();

        verify(conversationPersistenceService).appendAssistantMessage(
                any(ConversationPersistenceService.OpenConversation.class),
                any(UserContextService.UserContext.class),
                eq("help"),
                eq("answer"),
                eq(InteractionStatus.COMPLETED));
        verify(interactionService).completeInteraction(
                any(AIInteraction.class),
                eq("help"),
                eq("answer"),
                any(Instant.class));
        verify(tokenRateLimiter).releaseStream(ANONYMOUS_RATE_LIMIT_KEY);
    }

    @Test
    void persistsPartialErrorOutcomeAndPreservesProviderError() {
        prepareAnonymousStream(Flux.concat(
                Flux.just("partial"),
                Flux.error(new IllegalStateException("provider failed"))));

        StepVerifier.create(anonymousHelpStream())
                .expectNext("partial")
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && "provider failed".equals(error.getMessage()))
                .verify();

        verify(conversationPersistenceService).appendAssistantMessage(
                any(ConversationPersistenceService.OpenConversation.class),
                any(UserContextService.UserContext.class),
                eq("help"),
                eq("partial"),
                eq(InteractionStatus.ERROR));
        verify(interactionService).failInteraction(
                any(AIInteraction.class),
                eq("help"),
                eq("partial"),
                any(Instant.class));
        verify(tokenRateLimiter).releaseStream(ANONYMOUS_RATE_LIMIT_KEY);
    }

    @Test
    void terminalPersistenceFailureDoesNotReplaceProviderSuccess() {
        prepareAnonymousStream(Flux.just("answer"));
        when(conversationPersistenceService.appendAssistantMessage(
                any(ConversationPersistenceService.OpenConversation.class),
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                any(InteractionStatus.class)))
                .thenReturn(Mono.error(new IllegalStateException("conversation save failed")));
        when(interactionService.completeInteraction(
                any(AIInteraction.class),
                anyString(),
                anyString(),
                any(Instant.class)))
                .thenReturn(Mono.error(new IllegalStateException("interaction save failed")));

        StepVerifier.create(anonymousHelpStream())
                .expectNext("answer")
                .verifyComplete();
    }

    @Test
    void terminalPersistenceFailureDoesNotReplaceProviderError() {
        IllegalStateException providerError =
                new IllegalStateException("provider failed");
        prepareAnonymousStream(Flux.concat(
                Flux.just("partial"),
                Flux.error(providerError)));
        when(conversationPersistenceService.appendAssistantMessage(
                any(ConversationPersistenceService.OpenConversation.class),
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                any(InteractionStatus.class)))
                .thenReturn(Mono.error(new IllegalStateException("conversation save failed")));
        when(interactionService.failInteraction(
                any(AIInteraction.class),
                anyString(),
                anyString(),
                any(Instant.class)))
                .thenReturn(Mono.error(new IllegalStateException("interaction save failed")));

        StepVerifier.create(anonymousHelpStream())
                .expectNext("partial")
                .expectErrorSatisfies(error -> assertEquals(providerError, error))
                .verify();
    }

    @Test
    void persistsPartialCancellationAndReleasesConcurrentStreamLease() {
        prepareAnonymousStream(Flux.concat(
                Flux.just("partial"),
                Flux.never()));

        StepVerifier.create(anonymousHelpStream())
                .expectNext("partial")
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();

        verify(conversationPersistenceService, timeout(1000)).appendAssistantMessage(
                any(ConversationPersistenceService.OpenConversation.class),
                any(UserContextService.UserContext.class),
                eq("help"),
                eq("partial"),
                eq(InteractionStatus.CANCELLED));
        verify(interactionService, timeout(1000)).cancelInteraction(
                any(AIInteraction.class),
                eq("help"),
                eq("partial"),
                any(Instant.class));
        verify(tokenRateLimiter, timeout(1000))
                .releaseStream(ANONYMOUS_RATE_LIMIT_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/genai/chatbot/receptionist",
            "/genai/chatbot/aidentist",
            "/genai/chatbot/triage",
            "/genai/chatbot/documentation/summarize"
    })
    void rejectsAnonymousProtectedEndpoints(String path) {
        webTestClient.post()
                .uri(path)
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .bodyValue("private clinical prompt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void forgedIdentityHeadersDoNotAuthenticateProtectedEndpoint() {
        webTestClient.post()
                .uri("/genai/chatbot/triage")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-User-ID", "42")
                .header("X-User-Roles", "DENTIST")
                .bodyValue("private clinical prompt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsAnonymousHelpWithoutServiceCredentialBeforeProofUse() {
        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .bodyValue("help")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(anonymousSessionRegistry, never()).requireGatewayIssuedSession(any(), any());
    }

    @Test
    void serviceCredentialCannotAuthenticateAnotherRoute() {
        webTestClient.post()
                .uri("/genai/chatbot/triage")
                .contentType(MediaType.TEXT_PLAIN)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .bodyValue("symptoms")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void authenticatedUserJwtHelpUsesUserPathWithoutServiceCredentialOrProof() {
        jwtClient("42", 9L, "PATIENT")
                .post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .bodyValue("help")
                .exchange()
                .expectStatus().isOk();

        verify(tokenRateLimiter).tryConsume(eq("user:42"), anyLong());
        verify(anonymousSessionRegistry, never()).requireGatewayIssuedSession(any(), any());
    }

    @Test
    void allowsOnlyExactAnonymousPostForHelpAtServiceBoundary() {
        webTestClient.get()
                .uri("/genai/chatbot/help")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.post()
                .uri("/genai/chatbot/help/extra")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("help")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @ParameterizedTest
    @MethodSource("allowedRoleCases")
    void allowsOnlyApprovedRoles(String path, String role) {
        jwtClient("42", 9L, role)
                .post()
                .uri(path)
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .bodyValue("authorized prompt")
                .exchange()
                .expectStatus().isOk();
    }

    @ParameterizedTest
    @MethodSource("deniedRoleCases")
    void rejectsAuthenticatedButUnauthorizedRoles(String path, String role) {
        jwtClient("42", 9L, role)
                .post()
                .uri(path)
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .bodyValue("unauthorized prompt")
                .exchange()
                .expectStatus().isForbidden();

        verify(tokenRateLimiter, never()).tryConsume(anyString(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/genai/chatbot/receptionist",
            "/genai/chatbot/aidentist",
            "/genai/chatbot/triage",
            "/genai/chatbot/documentation/summarize"
    })
    void rejectsJwtWithoutApprovedRole(String path) {
        jwtClient("42", 9L)
                .post()
                .uri(path)
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .bodyValue("unauthorized prompt")
                .exchange()
                .expectStatus().isForbidden();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/genai/chatbot/aidentist",
            "/genai/chatbot/documentation/summarize"
    })
    void forgedDentistHeaderCannotElevatePatientRole(String path) {
        jwtClient("42", 9L, "PATIENT")
                .post()
                .uri(path)
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-User-Roles", "DENTIST")
                .bodyValue("unauthorized clinical prompt")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void allowsMultiRoleJwtWhenOneVerifiedRoleIsApproved() {
        jwtClient("42", 9L, "PATIENT", "DENTIST")
                .post()
                .uri("/genai/chatbot/aidentist")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .bodyValue("clinical prompt")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void methodSecurityRejectsWrongRoleWithoutWebFilter() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("42")
                .claim("roles", List.of("PATIENT"))
                .claim("clinicId", 9L)
                .build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
        var request = MockServerHttpRequest.post("/genai/chatbot/aidentist")
                .header("X-Session-ID", SESSION_ID)
                .build();

        StepVerifier.create(Flux.defer(() -> genAIController.aiDentist(
                                "clinical prompt",
                                request,
                                jwt))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .expectError(AccessDeniedException.class)
                .verify();

        verify(tokenRateLimiter, never()).tryConsume(anyString(), anyLong());
    }

    @Test
    void rejectsInvalidBearerTokenEvenForPublicHelp() {
        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .headers(headers -> headers.setBearerAuth("invalid"))
                .bodyValue("help")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void deniesUnlistedRoutesForAuthenticatedUsers() {
        jwtClient("42", 9L, "PATIENT")
                .post()
                .uri("/genai/chatbot/unlisted")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("prompt")
                .exchange()
                .expectStatus().isForbidden();
    }

    @ParameterizedTest
    @MethodSource("protectedEndpointCases")
    void protectedEndpointsUseVerifiedScopeAndContextualOrchestration(
            String path,
            String role,
            String agent) {
        when(tokenRateLimiter.tryConsume(anyString(), anyLong())).thenReturn(Mono.just(true));
        when(chatService.streamChatWithContext(
                eq(agent),
                anyString(),
                anyList(),
                isNull(),
                any(UserContextService.UserContext.class)))
                .thenReturn(Flux.empty());

        jwtClient("42", 9L, role)
                .post()
                .uri(path)
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-User-ID", "forged-user")
                .header("X-Clinic-ID", "999")
                .bodyValue("private clinical prompt")
                .exchange()
                .expectStatus().isOk();

        verify(tokenRateLimiter).tryConsume(eq("user:42"), anyLong());

        var contextCaptor = org.mockito.ArgumentCaptor.forClass(
                UserContextService.UserContext.class);
        verify(conversationPersistenceService).openConversation(
                contextCaptor.capture(),
                eq(agent),
                eq("private clinical prompt"),
                anyString());
        verify(chatService).streamChatWithContext(
                eq(agent),
                anyString(),
                anyList(),
                isNull(),
                any(UserContextService.UserContext.class));

        UserContextService.UserContext context = contextCaptor.getValue();
        assertEquals(SESSION_ID, context.getSessionId());
        assertEquals("42", context.getUserId());
        assertEquals("9", context.getClinicId());
        assertTrue(context.getRoles().contains(role));

        verify(chatService, never()).streamChat(
                anyString(), anyString(), anyList(), any(), anyString(), any());
    }

    @Test
    void opensAnonymousHelpConversationWithSessionAndAgentScope() {
        when(tokenRateLimiter.tryConsume(anyString(), anyLong())).thenReturn(Mono.just(true));
        when(chatService.streamChatWithContext(
                eq("help"),
                anyString(),
                anyList(),
                isNull(),
                any(UserContextService.UserContext.class)))
                .thenReturn(Flux.empty());

        webTestClient.post()
                .uri("/genai/chatbot/help")
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Session-ID", SESSION_ID)
                .header("X-Gateway-Anonymous-Proof", PROOF)
                .header(GenAIServiceJwtDecoder.HEADER_NAME, "Bearer service-token")
                .bodyValue("help")
                .exchange()
                .expectStatus().isOk();

        var contextCaptor = org.mockito.ArgumentCaptor.forClass(
                UserContextService.UserContext.class);
        verify(conversationPersistenceService).openConversation(
                contextCaptor.capture(),
                eq("help"),
                eq("help"),
                anyString());

        UserContextService.UserContext context = contextCaptor.getValue();
        assertEquals(SESSION_ID, context.getSessionId());
        assertEquals(SOURCE_FINGERPRINT,
                context.getAnonymousSourceFingerprint());
        assertNull(context.getUserId());
        assertNull(context.getClinicId());
    }

    @Test
    void initialConversationPersistenceFailurePreventsProviderWork() {
        when(tokenRateLimiter.tryConsume(anyString(), anyLong())).thenReturn(Mono.just(true));
        when(conversationPersistenceService.openConversation(
                any(UserContextService.UserContext.class),
                anyString(),
                anyString(),
                anyString()))
                .thenReturn(Mono.error(new IllegalStateException("mongo failed")));

        StepVerifier.create(anonymousHelpStream())
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && "mongo failed".equals(error.getMessage()))
                .verify();

        verify(interactionService, never()).startInteraction(
                anyString(),
                anyString(),
                any(ConversationPersistenceService.OpenConversation.class),
                any(),
                any(UserContextService.UserContext.class),
                any(Instant.class));
        verify(chatService, never()).streamChatWithContext(
                anyString(), anyString(), anyList(), any(), any());
    }

    private void prepareAnonymousStream(Flux<String> providerStream) {
        when(tokenRateLimiter.tryConsume(anyString(), anyLong())).thenReturn(Mono.just(true));
        when(chatService.streamChatWithContext(
                eq("help"),
                anyString(),
                anyList(),
                isNull(),
                any(UserContextService.UserContext.class)))
                .thenReturn(providerStream);
    }

    private Flux<String> anonymousHelpStream() {
        return genAIController.help(
                "help",
                anonymousRequest(SESSION_ID, PROOF),
                null);
    }

    private MockServerHttpRequest anonymousRequest(
            String sessionId,
            String proof) {
        return MockServerHttpRequest.post("/genai/chatbot/help")
                .header("X-Session-ID", sessionId)
                .header("X-Gateway-Anonymous-Proof", proof)
                .build();
    }

    private AnonymousSessionRegistry.VerifiedAnonymousSession verifiedSession(
            String sessionId) {
        return new AnonymousSessionRegistry.VerifiedAnonymousSession(
                sessionId,
                SOURCE_FINGERPRINT);
    }

    private Jwt serviceJwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("service-token")
                .header("alg", "RS256")
                .header("kid", "service-key")
                .issuer("https://api-gateway.dentistdss.internal")
                .subject("api-gateway")
                .audience(List.of("genai-service"))
                .claim("jti", "service-jti")
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plusSeconds(30))
                .claim("tokenType", "service")
                .claim("scope", "genai:anonymous-help")
                .build();
    }

    private WebTestClient jwtClient(String subject, Long clinicId, String... roles) {
        SimpleGrantedAuthority[] authorities = Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toArray(SimpleGrantedAuthority[]::new);

        return webTestClient.mutateWith(mockJwt()
                .jwt(jwt -> {
                    jwt.subject(subject)
                            .claim("email", "user@example.com")
                            .claim("roles", Arrays.asList(roles));
                    if (clinicId != null) {
                        jwt.claim("clinicId", clinicId);
                    }
                })
                .authorities(authorities));
    }

    private ConversationPersistenceService.OpenConversation openConversation(
            UserContextService.UserContext context,
            String agent,
            String turnId) {
        Conversation conversation = new Conversation();
        conversation.setId("conversation-1");
        conversation.setSessionId(context.getSessionId());
        conversation.setUserId(context.getUserId());
        conversation.setClinicId(context.getClinicId());
        conversation.setAgent(agent);
        conversation.setMessages(List.of());
        return new ConversationPersistenceService.OpenConversation(
                conversation,
                List.of(),
                turnId);
    }

    private static Stream<Arguments> allowedRoleCases() {
        return Stream.of(
                Arguments.of("/genai/chatbot/receptionist", "PATIENT"),
                Arguments.of("/genai/chatbot/aidentist", "DENTIST"),
                Arguments.of("/genai/chatbot/aidentist", "CLINIC_ADMIN"),
                Arguments.of("/genai/chatbot/triage", "PATIENT"),
                Arguments.of("/genai/chatbot/triage", "DENTIST"),
                Arguments.of("/genai/chatbot/triage", "RECEPTIONIST"),
                Arguments.of("/genai/chatbot/documentation/summarize", "DENTIST"),
                Arguments.of("/genai/chatbot/documentation/summarize", "CLINIC_ADMIN"),
                Arguments.of("/genai/chatbot/documentation/summarize", "RECEPTIONIST"));
    }

    private static Stream<Arguments> deniedRoleCases() {
        return Stream.of(
                Arguments.of("/genai/chatbot/receptionist", "DENTIST"),
                Arguments.of("/genai/chatbot/receptionist", "RECEPTIONIST"),
                Arguments.of("/genai/chatbot/receptionist", "CLINIC_ADMIN"),
                Arguments.of("/genai/chatbot/receptionist", "SYSTEM_ADMIN"),
                Arguments.of("/genai/chatbot/aidentist", "PATIENT"),
                Arguments.of("/genai/chatbot/aidentist", "RECEPTIONIST"),
                Arguments.of("/genai/chatbot/aidentist", "SYSTEM_ADMIN"),
                Arguments.of("/genai/chatbot/triage", "CLINIC_ADMIN"),
                Arguments.of("/genai/chatbot/triage", "SYSTEM_ADMIN"),
                Arguments.of("/genai/chatbot/documentation/summarize", "PATIENT"),
                Arguments.of("/genai/chatbot/documentation/summarize", "SYSTEM_ADMIN"));
    }

    private static Stream<Arguments> protectedEndpointCases() {
        return Stream.of(
                Arguments.of("/genai/chatbot/receptionist", "PATIENT", "receptionist"),
                Arguments.of("/genai/chatbot/aidentist", "DENTIST", "aidentist"),
                Arguments.of("/genai/chatbot/triage", "PATIENT", "triage"),
                Arguments.of(
                        "/genai/chatbot/documentation/summarize",
                        "RECEPTIONIST",
                        "documentation"));
    }
}
