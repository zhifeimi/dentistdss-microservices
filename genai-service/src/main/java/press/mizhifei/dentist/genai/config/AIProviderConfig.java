package press.mizhifei.dentist.genai.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
@ConfigurationProperties(prefix = "genai.providers")
@Data
public class AIProviderConfig {

    private OpenAiConfig openAi = new OpenAiConfig();

    @Data
    public static class OpenAiConfig {
        private boolean enabled = true;
    }

    @Bean("primaryChatClient")
    @Primary
    public ChatClient primaryChatClient(ChatClient.Builder chatClientBuilder) {
        if (!openAi.enabled) {
            throw new IllegalStateException("OpenAI-compatible provider is disabled");
        }
        log.info("Configuring OpenAI-compatible ChatClient");
        return chatClientBuilder.build();
    }
}
