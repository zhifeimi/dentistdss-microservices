package press.mizhifei.dentist.genai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
public class GenAIPromptValidator {

    private final int maxCharacters;

    public GenAIPromptValidator(
            @Value("${genai.prompt.max-characters:8000}")
            int maxCharacters) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException(
                    "GenAI maximum prompt length must be positive");
        }
        this.maxCharacters = maxCharacters;
    }

    public Mono<Void> validate(String prompt) {
        return Mono.defer(() -> {
            if (!StringUtils.hasText(prompt)) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Prompt must not be blank"));
            }
            if (prompt.length() > maxCharacters) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONTENT_TOO_LARGE,
                        "Prompt exceeds maximum length"));
            }
            return Mono.empty();
        });
    }
}
