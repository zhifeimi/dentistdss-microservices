package press.mizhifei.dentist.genai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import press.mizhifei.dentist.genai.model.AIInteraction;
import press.mizhifei.dentist.genai.model.InteractionStatus;
import press.mizhifei.dentist.genai.repository.AIInteractionRepository;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenAIInteractionService {

    private static final String STREAM_ERROR_CODE = "GENAI_STREAM_FAILED";

    private final AIInteractionRepository aiInteractionRepository;

    public Mono<AIInteraction> startInteraction(
            String agent,
            String userPrompt,
            ConversationPersistenceService.OpenConversation openConversation,
            String apiProvidedContext,
            UserContextService.UserContext userContext,
            Instant startTime) {
        AIInteraction interaction = newInteraction(
                agent,
                userPrompt,
                openConversation.conversation().getId(),
                apiProvidedContext,
                userContext.getSessionId(),
                numericUserId(userContext),
                userContext.getClinicId(),
                openConversation.history().size(),
                startTime);
        return save(interaction, agent);
    }

    public Mono<AIInteraction> startStandaloneInteraction(
            String agent,
            String userPrompt,
            String apiProvidedContext,
            String sessionId,
            Long userId,
            int historySize,
            Instant startTime) {
        return save(newInteraction(
                agent,
                userPrompt,
                null,
                apiProvidedContext,
                sessionId,
                userId,
                null,
                historySize,
                startTime), agent);
    }

    public Mono<Void> completeInteraction(
            AIInteraction interaction,
            String userPrompt,
            String response,
            Instant startTime) {
        return finishInteraction(
                interaction,
                userPrompt,
                response,
                startTime,
                InteractionStatus.COMPLETED,
                null);
    }

    public Mono<Void> failInteraction(
            AIInteraction interaction,
            String userPrompt,
            String partialResponse,
            Instant startTime) {
        return finishInteraction(
                interaction,
                userPrompt,
                partialResponse,
                startTime,
                InteractionStatus.ERROR,
                STREAM_ERROR_CODE);
    }

    public Mono<Void> cancelInteraction(
            AIInteraction interaction,
            String userPrompt,
            String partialResponse,
            Instant startTime) {
        return finishInteraction(
                interaction,
                userPrompt,
                partialResponse,
                startTime,
                InteractionStatus.CANCELLED,
                null);
    }

    public Mono<Void> recordCompletedInteraction(
            String agent,
            String userPrompt,
            String response,
            String apiProvidedContext,
            String sessionId,
            Long userId,
            int historySize,
            Instant startTime) {
        AIInteraction interaction = newInteraction(
                agent,
                userPrompt,
                null,
                apiProvidedContext,
                sessionId,
                userId,
                null,
                historySize,
                startTime);
        applyTerminalState(
                interaction,
                userPrompt,
                response,
                startTime,
                InteractionStatus.COMPLETED,
                null);
        return save(interaction, agent).then();
    }

    private AIInteraction newInteraction(
            String agent,
            String userPrompt,
            String conversationId,
            String apiProvidedContext,
            String sessionId,
            Long userId,
            String clinicId,
            int historySize,
            Instant startTime) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("historySize", historySize);
        metadata.put("hasApiProvidedContext",
                apiProvidedContext != null && !apiProvidedContext.isBlank());

        return AIInteraction.builder()
                .userId(userId)
                .sessionId(interactionSessionId(sessionId))
                .clinicId(clinicId)
                .conversationId(conversationId)
                .interactionType(determineInteractionType(agent))
                .aiModel(agent)
                .inputText(userPrompt)
                .aiResponse("")
                .status(InteractionStatus.IN_PROGRESS)
                .tokensUsed(estimatedTokens(userPrompt, ""))
                .responseTimeMs(0)
                .metadata(metadata)
                .createdAt(LocalDateTime.ofInstant(startTime, ZoneOffset.UTC))
                .build();
    }

    private Mono<Void> finishInteraction(
            AIInteraction interaction,
            String userPrompt,
            String response,
            Instant startTime,
            InteractionStatus status,
            String errorCode) {
        applyTerminalState(
                interaction,
                userPrompt,
                response,
                startTime,
                status,
                errorCode);
        return save(interaction, interaction.getAiModel()).then();
    }

    private void applyTerminalState(
            AIInteraction interaction,
            String userPrompt,
            String response,
            Instant startTime,
            InteractionStatus status,
            String errorCode) {
        Instant terminalAt = Instant.now();
        interaction.setAiResponse(response);
        interaction.setStatus(status);
        interaction.setTerminalAt(terminalAt);
        interaction.setErrorCode(errorCode);
        interaction.setTokensUsed(estimatedTokens(userPrompt, response));
        interaction.setResponseTimeMs(saturatedInt(
                Duration.between(startTime, terminalAt).toMillis()));
    }

    private Mono<AIInteraction> save(AIInteraction interaction, String agent) {
        return aiInteractionRepository.save(interaction)
                .doOnSuccess(saved -> log.debug(
                        "Saved GenAI interaction {} with status {}",
                        saved.getId(),
                        saved.getStatus()))
                .doOnError(error -> log.error(
                        "Failed to save GenAI interaction for agent {}",
                        agent,
                        error));
    }

    private Long numericUserId(UserContextService.UserContext userContext) {
        return userContext.getUserId() == null
                ? null
                : Long.valueOf(userContext.getUserId());
    }

    private UUID interactionSessionId(String sessionId) {
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException ex) {
            return UUID.nameUUIDFromBytes(
                    ("dentistdss-genai:" + sessionId)
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private int estimatedTokens(String prompt, String response) {
        long estimated = ((long) prompt.length() + response.length()) / 4L;
        return saturatedInt(estimated);
    }

    private int saturatedInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private String determineInteractionType(String agent) {
        return switch (agent.toLowerCase()) {
            case "triage" -> "TRIAGE";
            case "help" -> "FAQ";
            case "receptionist" -> "RECEPTIONIST_CHAT";
            case "aidentist" -> "DECISION_SUPPORT";
            case "documentation" -> "DOCUMENTATION_ASSISTANCE";
            default -> "GENERAL_CHAT";
        };
    }
}
