package press.mizhifei.dentist.genai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import press.mizhifei.dentist.genai.config.AIProviderConfig;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AIProviderServiceTest {

    @Mock
    private ChatClient chatClient;

    private AIProviderService providerService;

    @BeforeEach
    void setUp() {
        AIProviderConfig config = new AIProviderConfig();
        config.getOpenAi().setEnabled(false);
        providerService = new AIProviderService(config, chatClient);
    }

    @Test
    void chatRejectsWhenOpenAiDisabled() {
        StepVerifier.create(providerService.chat(new Prompt("test")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("OpenAI-compatible provider is disabled"))
                .verify();

        verifyNoInteractions(chatClient);
    }

    @Test
    void streamChatRejectsWhenOpenAiDisabled() {
        StepVerifier.create(providerService.streamChat(new Prompt("test")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("OpenAI-compatible provider is disabled"))
                .verify();

        verifyNoInteractions(chatClient);
    }
}
