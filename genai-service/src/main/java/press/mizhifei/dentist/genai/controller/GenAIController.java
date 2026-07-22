package press.mizhifei.dentist.genai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import press.mizhifei.dentist.genai.model.AIInteraction;
import press.mizhifei.dentist.genai.model.InteractionStatus;
import press.mizhifei.dentist.genai.service.ChatService;
import press.mizhifei.dentist.genai.service.ConversationPersistenceService;
import press.mizhifei.dentist.genai.service.GenAIInteractionService;
import press.mizhifei.dentist.genai.service.GenAIPromptValidator;
import press.mizhifei.dentist.genai.service.TokenRateLimiter;
import press.mizhifei.dentist.genai.service.UserContextService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Slf4j
@RestController
@RequestMapping("/genai/chatbot")
@RequiredArgsConstructor
public class GenAIController {

    private final ChatService chatService;
    private final TokenRateLimiter tokenRateLimiter;
    private final ConversationPersistenceService conversationPersistenceService;
    private final GenAIInteractionService interactionService;
    private final GenAIPromptValidator promptValidator;
    private final UserContextService userContextService;

    @PostMapping(value = "/help", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> help(
            @RequestBody String prompt,
            ServerHttpRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt != null && !"service".equals(jwt.getClaimAsString("tokenType"))) {
            UserContextService.UserContext userContext =
                    userContextService.extractUserContext(request, jwt);
            return rateLimitAndStream(
                    userContext,
                    prompt,
                    () -> streamAndPersistWithContext("help", userContext, prompt, null));
        }

        return userContextService.extractAnonymousUserContext(request)
                .flatMapMany(userContext -> rateLimitAndStream(
                        userContext,
                        prompt,
                        () -> streamAndPersistWithContext(
                                "help", userContext, prompt, null)));
    }

    @PreAuthorize("hasRole('PATIENT')")
    @PostMapping(value = "/receptionist", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> receptionist(
            @RequestBody String prompt,
            ServerHttpRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UserContextService.UserContext userContext =
                userContextService.extractUserContext(request, jwt);
        return rateLimitAndStream(
                userContext,
                prompt,
                () -> streamAndPersistWithContext(
                        "receptionist", userContext, prompt, null));
    }

    @PreAuthorize("hasAnyRole('DENTIST', 'CLINIC_ADMIN')")
    @PostMapping(value = "/aidentist", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> aiDentist(
            @RequestBody String prompt,
            ServerHttpRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UserContextService.UserContext userContext =
                userContextService.extractUserContext(request, jwt);
        return rateLimitAndStream(
                userContext,
                prompt,
                () -> streamAndPersistWithContext(
                        "aidentist", userContext, prompt, null));
    }

    @PreAuthorize("hasAnyRole('PATIENT', 'DENTIST', 'RECEPTIONIST')")
    @PostMapping(value = "/triage", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> triage(
            @RequestBody String symptoms,
            ServerHttpRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UserContextService.UserContext userContext =
                userContextService.extractUserContext(request, jwt);
        return rateLimitAndStream(
                userContext,
                symptoms,
                () -> streamAndPersistWithContext(
                        "triage", userContext, symptoms, null));
    }

    @PreAuthorize("hasAnyRole('DENTIST', 'CLINIC_ADMIN', 'RECEPTIONIST')")
    @PostMapping(value = "/documentation/summarize", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> summarizeDocumentation(
            @RequestBody String notes,
            ServerHttpRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UserContextService.UserContext userContext =
                userContextService.extractUserContext(request, jwt);
        return rateLimitAndStream(
                userContext,
                notes,
                () -> streamAndPersistWithContext(
                        "documentation", userContext, notes, null));
    }

    private Flux<String> rateLimitAndStream(
            UserContextService.UserContext userContext,
            String prompt,
            Supplier<Flux<String>> streamSupplier) {
        String subjectKey = rateLimitKey(userContext);
        return promptValidator.validate(prompt)
                .then(Mono.defer(() -> tokenRateLimiter.tryConsume(
                        subjectKey,
                        estimateTokens(prompt))))
                .flatMapMany(allowed -> {
                    if (!allowed) {
                        return Flux.just(limitMessage());
                    }
                    return tokenRateLimiter.tryAcquireStream(subjectKey)
                            .flatMapMany(acquired -> {
                                if (!acquired) {
                                    return Flux.just(concurrentLimitMessage());
                                }
                                return Flux.usingWhen(
                                        Mono.just(subjectKey),
                                        ignored -> Flux.defer(streamSupplier),
                                        ignored -> releaseStream(subjectKey),
                                        (ignored, error) -> releaseStream(subjectKey),
                                        ignored -> releaseStream(subjectKey));
                            });
                });
    }

    private Mono<Void> releaseStream(String subjectKey) {
        return tokenRateLimiter.releaseStream(subjectKey)
                .onErrorResume(error -> {
                    log.error("Failed to release GenAI stream lease", error);
                    return Mono.empty();
                });
    }

    private Flux<String> streamAndPersistWithContext(
            String agent,
            UserContextService.UserContext userContext,
            String prompt,
            String apiProvidedContext) {
        return Flux.defer(() -> {
            Instant startTime = Instant.now();
            String turnId = UUID.randomUUID().toString();

            return conversationPersistenceService.openConversation(
                            userContext,
                            agent,
                            prompt,
                            turnId)
                    .flatMap(openConversation -> interactionService.startInteraction(
                                    agent,
                                    prompt,
                                    openConversation,
                                    apiProvidedContext,
                                    userContext,
                                    startTime)
                            .map(interaction -> new StreamState(
                                    agent,
                                    prompt,
                                    apiProvidedContext,
                                    userContext,
                                    startTime,
                                    openConversation,
                                    interaction,
                                    new StringBuilder())))
                    .flatMapMany(state -> Flux.usingWhen(
                            Mono.just(state),
                            ignored -> Flux.defer(() -> chatService.streamChatWithContext(
                                            state.agent(),
                                            state.prompt(),
                                            state.openConversation().history(),
                                            state.apiProvidedContext(),
                                            state.userContext()))
                                    .doOnNext(state.response()::append),
                            this::completeStream,
                            (streamState, error) -> failStream(streamState),
                            this::cancelStream));
        });
    }

    private Mono<Void> completeStream(StreamState state) {
        return persistTerminalState(state, InteractionStatus.COMPLETED);
    }

    private Mono<Void> failStream(StreamState state) {
        return persistTerminalState(state, InteractionStatus.ERROR);
    }

    private Mono<Void> cancelStream(StreamState state) {
        return persistTerminalState(state, InteractionStatus.CANCELLED);
    }

    private Mono<Void> persistTerminalState(
            StreamState state,
            InteractionStatus status) {
        String response = state.response().toString();
        Mono<Void> conversationPersistence = conversationPersistenceService
                .appendAssistantMessage(
                        state.openConversation(),
                        state.userContext(),
                        state.agent(),
                        response,
                        status)
                .then();
        Mono<Void> interactionPersistence = switch (status) {
            case COMPLETED -> interactionService.completeInteraction(
                    state.interaction(),
                    state.prompt(),
                    response,
                    state.startTime());
            case ERROR -> interactionService.failInteraction(
                    state.interaction(),
                    state.prompt(),
                    response,
                    state.startTime());
            case CANCELLED -> interactionService.cancelInteraction(
                    state.interaction(),
                    state.prompt(),
                    response,
                    state.startTime());
            case IN_PROGRESS -> Mono.error(new IllegalArgumentException(
                    "IN_PROGRESS is not a terminal stream status"));
        };

        return Mono.whenDelayError(
                        conversationPersistence,
                        interactionPersistence)
                .onErrorResume(error -> {
                    log.error(
                            "Failed to persist terminal GenAI stream state for agent {} and status {}",
                            state.agent(),
                            status,
                            error);
                    return Mono.empty();
                });
    }

    private record StreamState(
            String agent,
            String prompt,
            String apiProvidedContext,
            UserContextService.UserContext userContext,
            Instant startTime,
            ConversationPersistenceService.OpenConversation openConversation,
            AIInteraction interaction,
            StringBuilder response) {
    }

    private String rateLimitKey(UserContextService.UserContext userContext) {
        if (userContext.isAuthenticated()) {
            return "user:" + userContext.getUserId();
        }
        if (!StringUtils.hasText(userContext.getAnonymousSourceFingerprint())) {
            throw new IllegalStateException(
                    "Verified anonymous source fingerprint is required");
        }
        return "source:" + userContext.getAnonymousSourceFingerprint();
    }

    private long estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    private String limitMessage() {
        return "You have reached the maximal inquiries. For a better user experience for every one of our users, we kindly suggest you ask more questions 3 minutes later.";
    }

    private String concurrentLimitMessage() {
        return "You already have the maximum number of active AI responses. Please wait for one to finish before trying again.";
    }
}
