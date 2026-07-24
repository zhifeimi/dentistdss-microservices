package press.mizhifei.dentist.genai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import press.mizhifei.dentist.genai.model.AIInteraction;
import press.mizhifei.dentist.genai.model.Conversation;
import press.mizhifei.dentist.genai.model.InteractionStatus;
import press.mizhifei.dentist.genai.repository.AIInteractionRepository;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenAIInteractionServiceTest {

    private static final String SESSION_ID =
            "abcdefghijklmnopqrstuvwxyzABCDEFGH012345678";

    private AIInteractionRepository interactionRepository;
    private GenAIInteractionService interactionService;

    @BeforeEach
    void setUp() {
        interactionRepository = mock(AIInteractionRepository.class);
        interactionService = new GenAIInteractionService(interactionRepository);
        when(interactionRepository.save(any(AIInteraction.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void startsScopedInteractionWithoutRetainingRawApiContext() {
        Instant startTime = Instant.parse("2026-07-16T00:00:00Z");
        UserContextService.UserContext context =
                UserContextService.UserContext.builder()
                        .sessionId(SESSION_ID)
                        .userId("42")
                        .clinicId("9")
                        .roles(List.of("DENTIST"))
                        .authenticated(true)
                        .build();
        Conversation conversation = new Conversation();
        conversation.setId("conversation-1");
        ConversationPersistenceService.OpenConversation openConversation =
                new ConversationPersistenceService.OpenConversation(
                        conversation,
                        List.of(new Conversation.Message()),
                        "turn-1");

        StepVerifier.create(interactionService.startInteraction(
                        "aidentist",
                        "clinical prompt",
                        openConversation,
                        "private clinical context",
                        context,
                        startTime))
                .assertNext(interaction -> {
                    assertEquals(42L, interaction.getUserId());
                    assertEquals(namedSessionId(SESSION_ID),
                            interaction.getSessionId());
                    assertEquals("9", interaction.getClinicId());
                    assertEquals("conversation-1",
                            interaction.getConversationId());
                    assertEquals("DECISION_SUPPORT",
                            interaction.getInteractionType());
                    assertEquals("aidentist", interaction.getAiModel());
                    assertEquals("clinical prompt",
                            interaction.getInputText());
                    assertEquals("", interaction.getAiResponse());
                    assertEquals(InteractionStatus.IN_PROGRESS,
                            interaction.getStatus());
                    assertNull(interaction.getTerminalAt());
                    assertNull(interaction.getErrorCode());
                    assertEquals(1,
                            interaction.getMetadata().get("historySize"));
                    assertEquals(true,
                            interaction.getMetadata()
                                    .get("hasApiProvidedContext"));
                    assertFalse(interaction.getMetadata()
                            .containsValue("private clinical context"));
                    assertEquals(LocalDateTime.ofInstant(
                                    startTime,
                                    ZoneOffset.UTC),
                            interaction.getCreatedAt());
                })
                .verifyComplete();
    }

    @Test
    void completesInteractionWithResponseAndTerminalMetrics() {
        AIInteraction interaction = inProgressInteraction("triage");

        StepVerifier.create(interactionService.completeInteraction(
                        interaction,
                        "prompt",
                        "complete response",
                        Instant.EPOCH))
                .verifyComplete();

        AIInteraction saved = capturedInteraction();
        assertEquals(InteractionStatus.COMPLETED, saved.getStatus());
        assertEquals("complete response", saved.getAiResponse());
        assertNull(saved.getErrorCode());
        assertNotNull(saved.getTerminalAt());
        assertEquals(Integer.MAX_VALUE, saved.getResponseTimeMs());
        assertEquals(("prompt".length() + "complete response".length()) / 4,
                saved.getTokensUsed());
    }

    @Test
    void failsInteractionWithPartialResponseAndStableErrorCode() {
        AIInteraction interaction = inProgressInteraction("documentation");

        StepVerifier.create(interactionService.failInteraction(
                        interaction,
                        "prompt",
                        "partial response",
                        Instant.now().minusSeconds(1)))
                .verifyComplete();

        AIInteraction saved = capturedInteraction();
        assertEquals(InteractionStatus.ERROR, saved.getStatus());
        assertEquals("partial response", saved.getAiResponse());
        assertEquals("GENAI_STREAM_FAILED", saved.getErrorCode());
        assertNotNull(saved.getTerminalAt());
    }

    @Test
    void cancelsInteractionWithPartialResponseAndNoErrorDetail() {
        AIInteraction interaction = inProgressInteraction("help");

        StepVerifier.create(interactionService.cancelInteraction(
                        interaction,
                        "prompt",
                        "partial response",
                        Instant.now().minusSeconds(1)))
                .verifyComplete();

        AIInteraction saved = capturedInteraction();
        assertEquals(InteractionStatus.CANCELLED, saved.getStatus());
        assertEquals("partial response", saved.getAiResponse());
        assertNull(saved.getErrorCode());
        assertNotNull(saved.getTerminalAt());
    }

    @Test
    void recordsStandaloneCompletionAndClampsNegativeDuration() {
        Instant futureStart = Instant.now().plusSeconds(60);

        StepVerifier.create(interactionService.recordCompletedInteraction(
                        "receptionist",
                        "prompt",
                        "response",
                        null,
                        SESSION_ID,
                        null,
                        0,
                        futureStart))
                .verifyComplete();

        AIInteraction saved = capturedInteraction();
        assertNull(saved.getUserId());
        assertNull(saved.getClinicId());
        assertNull(saved.getConversationId());
        assertEquals("RECEPTIONIST_CHAT", saved.getInteractionType());
        assertEquals(InteractionStatus.COMPLETED, saved.getStatus());
        assertEquals(0, saved.getResponseTimeMs());
        assertEquals(false,
                saved.getMetadata().get("hasApiProvidedContext"));
    }

    private AIInteraction capturedInteraction() {
        var captor = forClass(AIInteraction.class);
        verify(interactionRepository).save(captor.capture());
        return captor.getValue();
    }

    private AIInteraction inProgressInteraction(String agent) {
        return AIInteraction.builder()
                .id("interaction-1")
                .aiModel(agent)
                .inputText("prompt")
                .aiResponse("")
                .status(InteractionStatus.IN_PROGRESS)
                .build();
    }

    private UUID namedSessionId(String sessionId) {
        return UUID.nameUUIDFromBytes(
                ("dentistdss-genai:" + sessionId)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
