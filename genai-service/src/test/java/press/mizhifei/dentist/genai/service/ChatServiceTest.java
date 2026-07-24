package press.mizhifei.dentist.genai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import press.mizhifei.dentist.genai.model.AIInteraction;
import press.mizhifei.dentist.genai.model.Conversation;
import press.mizhifei.dentist.genai.model.InteractionStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private ChatClient chatClient;
    private AIPromptService promptService;
    private GenAIInteractionService interactionService;
    private PromptOrchestrationService promptOrchestrationService;
    private AIProviderService aiProviderService;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        promptService = mock(AIPromptService.class);
        interactionService = mock(GenAIInteractionService.class);
        promptOrchestrationService = mock(PromptOrchestrationService.class);
        aiProviderService = mock(AIProviderService.class);
        chatService = new ChatService(
                chatClient,
                promptService,
                interactionService,
                promptOrchestrationService,
                aiProviderService);

        when(promptService.getSystemPrompt(anyString()))
                .thenReturn("system prompt");
    }

    @Test
    void keepsLegacyStreamStateIndependentPerSubscription() {
        AIInteraction firstInteraction = interaction("interaction-1");
        AIInteraction secondInteraction = interaction("interaction-2");
        whenStartStandaloneInteraction()
                .thenReturn(
                        Mono.just(firstInteraction),
                        Mono.just(secondInteraction));
        when(aiProviderService.streamChat(any(Prompt.class)))
                .thenReturn(Flux.just("first"), Flux.just("second"));
        when(interactionService.completeInteraction(
                any(AIInteraction.class),
                anyString(),
                anyString(),
                any(Instant.class)))
                .thenReturn(Mono.empty());

        Flux<String> stream = chatService.streamChat(
                "help",
                "prompt",
                List.of(),
                null,
                "session-id",
                null);

        StepVerifier.create(stream)
                .expectNext("first")
                .verifyComplete();
        StepVerifier.create(stream)
                .expectNext("second")
                .verifyComplete();

        verify(interactionService).completeInteraction(
                eq(firstInteraction),
                eq("prompt"),
                eq("first"),
                any(Instant.class));
        verify(interactionService).completeInteraction(
                eq(secondInteraction),
                eq("prompt"),
                eq("second"),
                any(Instant.class));
    }

    @Test
    void persistsPartialLegacyResponseOnProviderError() {
        AIInteraction interaction = interaction("interaction-1");
        IllegalStateException providerFailure =
                new IllegalStateException("provider failed");
        whenStartStandaloneInteraction()
                .thenReturn(Mono.just(interaction));
        when(aiProviderService.streamChat(any(Prompt.class)))
                .thenReturn(Flux.concat(
                        Flux.just("partial"),
                        Flux.error(providerFailure)));
        when(interactionService.failInteraction(
                any(AIInteraction.class),
                anyString(),
                anyString(),
                any(Instant.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(chatService.streamChat(
                        "triage",
                        "prompt",
                        List.of(),
                        null,
                        "session-id",
                        42L))
                .expectNext("partial")
                .expectErrorSatisfies(error -> {
                    if (error != providerFailure) {
                        throw new AssertionError(
                                "Provider failure identity was replaced");
                    }
                })
                .verify();

        verify(interactionService).failInteraction(
                eq(interaction),
                eq("prompt"),
                eq("partial"),
                any(Instant.class));
        verify(interactionService, never()).completeInteraction(
                any(), anyString(), anyString(), any());
    }

    @Test
    void persistsPartialLegacyResponseOnCancellation() {
        AIInteraction interaction = interaction("interaction-1");
        whenStartStandaloneInteraction()
                .thenReturn(Mono.just(interaction));
        when(aiProviderService.streamChat(any(Prompt.class)))
                .thenReturn(Flux.concat(
                        Flux.just("partial"),
                        Flux.never()));
        when(interactionService.cancelInteraction(
                any(AIInteraction.class),
                anyString(),
                anyString(),
                any(Instant.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(chatService.streamChat(
                        "help",
                        "prompt",
                        List.of(),
                        null,
                        "session-id",
                        null))
                .expectNext("partial")
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();

        verify(interactionService).cancelInteraction(
                eq(interaction),
                eq("prompt"),
                eq("partial"),
                any(Instant.class));
        verify(interactionService, never()).completeInteraction(
                any(), anyString(), anyString(), any());
    }

    @Test
    void terminalPersistenceFailureDoesNotReplaceProviderSuccess() {
        AIInteraction interaction = interaction("interaction-1");
        whenStartStandaloneInteraction()
                .thenReturn(Mono.just(interaction));
        when(aiProviderService.streamChat(any(Prompt.class)))
                .thenReturn(Flux.just("answer"));
        when(interactionService.completeInteraction(
                any(AIInteraction.class),
                anyString(),
                anyString(),
                any(Instant.class)))
                .thenReturn(Mono.error(new IllegalStateException(
                        "mongo unavailable")));

        StepVerifier.create(chatService.streamChat(
                        "help",
                        "prompt",
                        List.of(),
                        null,
                        "session-id",
                        null))
                .expectNext("answer")
                .verifyComplete();
    }

    @Test
    void terminalPersistenceFailureDoesNotReplaceProviderError() {
        AIInteraction interaction = interaction("interaction-1");
        IllegalStateException providerFailure =
                new IllegalStateException("provider failed");
        whenStartStandaloneInteraction()
                .thenReturn(Mono.just(interaction));
        when(aiProviderService.streamChat(any(Prompt.class)))
                .thenReturn(Flux.error(providerFailure));
        when(interactionService.failInteraction(
                any(AIInteraction.class),
                anyString(),
                anyString(),
                any(Instant.class)))
                .thenReturn(Mono.error(new IllegalStateException(
                        "mongo unavailable")));

        StepVerifier.create(chatService.streamChat(
                        "help",
                        "prompt",
                        List.of(),
                        null,
                        "session-id",
                        null))
                .expectErrorSatisfies(error -> {
                    if (error != providerFailure) {
                        throw new AssertionError(
                                "Provider failure identity was replaced");
                    }
                })
                .verify();
    }

    @Test
    void initialInteractionPersistenceFailurePreventsProviderWork() {
        whenStartStandaloneInteraction()
                .thenReturn(Mono.error(new IllegalStateException(
                        "mongo unavailable")));

        StepVerifier.create(chatService.streamChat(
                        "help",
                        "prompt",
                        List.of(),
                        null,
                        "session-id",
                        null))
                .expectErrorMessage("mongo unavailable")
                .verify();

        verify(aiProviderService, never()).streamChat(any(Prompt.class));
    }

    @Test
    void contextualStreamLeavesPersistenceToControllerLifecycle() {
        UserContextService.UserContext context =
                UserContextService.UserContext.builder()
                        .sessionId("session-id")
                        .userId("42")
                        .clinicId("9")
                        .roles(List.of("PATIENT"))
                        .authenticated(true)
                        .build();
        when(promptOrchestrationService.orchestratePrompt(
                "triage",
                context,
                "prompt",
                "context"))
                .thenReturn(Mono.just("orchestrated system prompt"));
        when(aiProviderService.streamChat(any(Prompt.class)))
                .thenReturn(Flux.just("answer"));

        StepVerifier.create(chatService.streamChatWithContext(
                        "triage",
                        "prompt",
                        List.of(),
                        "context",
                        context))
                .expectNext("answer")
                .verifyComplete();

        verifyNoInteractions(interactionService);
    }

    private org.mockito.stubbing.OngoingStubbing<Mono<AIInteraction>>
            whenStartStandaloneInteraction() {
        return when(interactionService.startStandaloneInteraction(
                anyString(),
                anyString(),
                nullable(String.class),
                anyString(),
                nullable(Long.class),
                anyInt(),
                any(Instant.class)));
    }

    private AIInteraction interaction(String id) {
        return AIInteraction.builder()
                .id(id)
                .aiModel("help")
                .status(InteractionStatus.IN_PROGRESS)
                .build();
    }
}
