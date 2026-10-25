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
        if (!config.getOpenAi().isEnabled()) {
            return Mono.error(new IllegalStateException("OpenAI-compatible provider is disabled"));
        }

        return Mono.fromSupplier(() -> chatClient.prompt(prompt).call().content())
                .doOnSuccess(response -> log.debug("OpenAI-compatible chat completed successfully"))
                .doOnError(error -> log.error("OpenAI-compatible chat failed"));
    }

    public Flux<String> streamChat(Prompt prompt) {
        if (!config.getOpenAi().isEnabled()) {
            return Flux.error(new IllegalStateException("OpenAI-compatible provider is disabled"));
        }

        return chatClient.prompt(prompt).stream().content()
                .doOnNext(chunk -> log.trace("OpenAI-compatible stream chunk received"))
                .doOnComplete(() -> log.debug("OpenAI-compatible stream completed"))
                .doOnError(error -> log.error("OpenAI-compatible stream failed"));
    }

    public ProviderStatus getProviderStatus() {
        return ProviderStatus.builder()
                .openAiEnabled(config.getOpenAi().isEnabled())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class ProviderStatus {
        private boolean openAiEnabled;
    }
}
