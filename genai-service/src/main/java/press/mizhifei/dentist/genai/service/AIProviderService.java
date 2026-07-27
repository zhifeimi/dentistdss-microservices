package press.mizhifei.dentist.genai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import press.mizhifei.dentist.genai.config.AIProviderConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIProviderService {

    private final AIProviderConfig config;

    @Qualifier("primaryChatClient")
    private final ChatClient chatClient;

    public Mono<String> chat(Prompt prompt) {
        if (!config.getGoogleGenAi().isEnabled()) {
            return Mono.error(new IllegalStateException("Google GenAI provider is disabled"));
        }

        return Mono.fromSupplier(() -> chatClient.prompt(prompt).call().content())
                .doOnSuccess(response -> log.debug("Google GenAI chat completed successfully"))
                .doOnError(error -> log.error("Google GenAI chat failed"));
    }

    public Flux<String> streamChat(Prompt prompt) {
        if (!config.getGoogleGenAi().isEnabled()) {
            return Flux.error(new IllegalStateException("Google GenAI provider is disabled"));
        }

        return chatClient.prompt(prompt).stream().content()
                .doOnNext(chunk -> log.trace("Google GenAI stream chunk received"))
                .doOnComplete(() -> log.debug("Google GenAI stream completed"))
                .doOnError(error -> log.error("Google GenAI stream failed"));
    }

    public ProviderStatus getProviderStatus() {
        return ProviderStatus.builder()
                .vertexAiEnabled(config.getGoogleGenAi().isEnabled())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class ProviderStatus {
        private boolean vertexAiEnabled;
    }
}
