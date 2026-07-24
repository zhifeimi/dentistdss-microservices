package press.mizhifei.dentist.genai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import press.mizhifei.dentist.genai.model.Conversation;
import press.mizhifei.dentist.genai.service.UserContextService.UserContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;
    private final AIPromptService promptService;
    private final GenAIInteractionService interactionService;
    private final PromptOrchestrationService promptOrchestrationService;
    private final AIProviderService aiProviderService;

    public Flux<String> streamChat(
            String agent,
            String userPrompt,
            List<Conversation.Message> history,
            String apiProvidedContext,
            String sessionId,
            Long userId) {
        return Flux.defer(() -> {
            Instant startTime = Instant.now();
            StringBuilder responseBuilder = new StringBuilder();
            List<Message> messages = buildMessages(
                    agent, userPrompt, history, apiProvidedContext);
            int historySize = history != null ? history.size() : 0;

            return interactionService.startStandaloneInteraction(
                            agent,
                            userPrompt,
                            apiProvidedContext,
                            sessionId,
                            userId,
                            historySize,
                            startTime)
                    .flatMapMany(interaction -> Flux.usingWhen(
                            Mono.just(interaction),
                            ignored -> aiProviderService
                                    .streamChat(new Prompt(messages))
                                    .doOnNext(responseBuilder::append),
                            ignored -> preserveTerminalSemantics(
                                    interactionService.completeInteraction(
                                            interaction,
                                            userPrompt,
                                            responseBuilder.toString(),
                                            startTime),
                                    agent),
                            (ignored, error) -> preserveTerminalSemantics(
                                    interactionService.failInteraction(
                                            interaction,
                                            userPrompt,
                                            responseBuilder.toString(),
                                            startTime),
                                    agent),
                            ignored -> preserveTerminalSemantics(
                                    interactionService.cancelInteraction(
                                            interaction,
                                            userPrompt,
                                            responseBuilder.toString(),
                                            startTime),
                                    agent)))
                    .doOnError(error -> log.error(
                            "Error in chat streaming for agent {}",
                            agent,
                            error));
        });
    }

    /**
     * Enhanced streaming chat with user context and prompt orchestration
     */
    public Flux<String> streamChatWithContext(
            String agent,
            String userPrompt,
            List<Conversation.Message> history,
            String apiProvidedContext,
            UserContext userContext) {
        return Flux.defer(() -> promptOrchestrationService
                .orchestratePrompt(
                        agent,
                        userContext,
                        userPrompt,
                        apiProvidedContext)
                .flatMapMany(orchestratedPrompt -> {
                    List<Message> messages = buildMessagesWithOrchestration(
                            orchestratedPrompt,
                            userPrompt,
                            history);
                    return aiProviderService.streamChat(new Prompt(messages));
                })
                .doOnError(error -> log.error(
                        "Error in enhanced chat streaming for agent {}",
                        agent,
                        error)));
    }

    public Mono<String> chat(String agent, String userPrompt, List<Conversation.Message> history, String apiProvidedContext, String sessionId, Long userId) {
        Instant startTime = Instant.now();
        List<Message> messages = buildMessages(agent, userPrompt, history, apiProvidedContext);
        
        return Mono.fromSupplier(() -> chatClient
                .prompt(new Prompt(messages))
                .call()
                .content())
                .flatMap(response -> interactionService.recordCompletedInteraction(
                                agent,
                                userPrompt,
                                response,
                                apiProvidedContext,
                                sessionId,
                                userId,
                                history != null ? history.size() : 0,
                                startTime)
                        .thenReturn(response))
                .doOnError(error -> {
                    log.error("Error in chat for agent {}: {}", agent, error.getMessage());
                });
    }

    private List<Message> buildMessages(String agent, String userPrompt, List<Conversation.Message> history, String apiProvidedContext) {
        List<Message> messages = new ArrayList<>();
        String systemPromptContent = promptService.getSystemPrompt(agent);

        if (apiProvidedContext != null && !apiProvidedContext.isEmpty()) {
            systemPromptContent += "\n\nContext: " + apiProvidedContext;
        }
        messages.add(new SystemMessage(systemPromptContent));

        if (history != null) {
            for (Conversation.Message msg : history) {
                if ("user".equalsIgnoreCase(msg.getRole())) {
                    messages.add(new UserMessage(msg.getContent()));
                } else if ("assistant".equalsIgnoreCase(msg.getRole())) {
                    messages.add(new AssistantMessage(msg.getContent()));
                }
            }
        }
        messages.add(new UserMessage(userPrompt)); // Current user prompt
        return messages;
    }

    /**
     * Builds messages with orchestrated system prompt
     */
    private List<Message> buildMessagesWithOrchestration(String orchestratedSystemPrompt, String userPrompt, List<Conversation.Message> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(orchestratedSystemPrompt));

        if (history != null) {
            for (Conversation.Message msg : history) {
                if ("user".equalsIgnoreCase(msg.getRole())) {
                    messages.add(new UserMessage(msg.getContent()));
                } else if ("assistant".equalsIgnoreCase(msg.getRole())) {
                    messages.add(new AssistantMessage(msg.getContent()));
                }
            }
        }
        messages.add(new UserMessage(userPrompt)); // Current user prompt
        return messages;
    }
    
    // Backward compatibility methods
    public Flux<String> streamChat(String agent, String userPrompt, String apiProvidedContext) {
        // For backward compatibility, session ID is random, userId is null, history is empty.
        return streamChat(agent, userPrompt, new ArrayList<>(), apiProvidedContext, UUID.randomUUID().toString(), null);
    }
    
    public Mono<String> chat(String agent, String userPrompt, String apiProvidedContext) {
        // For backward compatibility, session ID is random, userId is null, history is empty.
        return chat(agent, userPrompt, new ArrayList<>(), apiProvidedContext, UUID.randomUUID().toString(), null);
    }
        
    private Mono<Void> preserveTerminalSemantics(
            Mono<Void> persistence,
            String agent) {
        return persistence.onErrorResume(error -> {
            log.error(
                    "Failed to persist terminal GenAI stream state for agent {}",
                    agent,
                    error);
            return Mono.empty();
        });
    }
} 