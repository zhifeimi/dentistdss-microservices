package press.mizhifei.dentist.genai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GENAI_LIVE_TEST", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false"
        })
class OpenAiCompatibleProviderIntegrationTest {

    @MockitoBean
    private PromptTemplateInitializationService promptTemplateInitializationService;

    @Autowired
    private AIProviderService providerService;

    @Test
    void streamsNonBlankResponseFromConfiguredOpenAiCompatibleEndpoint() {
        String response = providerService.streamChat(new Prompt("Reply with the single word OK."))
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block(Duration.ofSeconds(120));

        assertThat(response).isNotBlank();
    }
}
