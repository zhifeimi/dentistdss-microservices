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

    private GoogleGenAiConfig googleGenAi = new GoogleGenAiConfig();

    @Data
    public static class GoogleGenAiConfig {
        private boolean enabled = true;
        private String projectId;
        private String location = "us-central1";
        private String defaultModel = "gemini-2.5-flash";
        private int maxTokens = 4096;
        private double temperature = 0.7;
    }

    @Bean("primaryChatClient")
    @Primary
    public ChatClient primaryChatClient(ChatClient.Builder chatClientBuilder) {
        if (!googleGenAi.enabled) {
            throw new IllegalStateException("Google GenAI provider is disabled");
        }
        log.info("Configuring Google GenAI ChatClient in Vertex AI mode");
        return chatClientBuilder.build();
    }
}
