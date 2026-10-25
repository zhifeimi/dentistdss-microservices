package press.mizhifei.dentist.genai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.util.regex.Pattern;

@Component
public class GenAIPromptValidator {

    // ponytail: phrase heuristics only catch known attacks; the system
    // prompt guardrail in ChatService is the backstop for novel phrasings.
    private static final Pattern PROMPT_ATTACK = Pattern.compile(
            "\\b(ignore|disregard|forget|override|bypass)\\b.{0,40}"
                    + "\\b(previous|prior|above|earlier|system|your|all)\\b.{0,20}"
                    + "\\b(instructions?|rules|prompts?|directives|guidelines)\\b"
                    + "|\\b(reveal|show|print|repeat|output|leak)\\b.{0,40}"
                    + "\\b(system prompt|hidden instructions|initial instructions|your instructions)\\b"
                    + "|\\b(developer mode|jailbreak|do anything now)\\b"
                    + "|\\byou are now\\b.{0,20}\\b(dan|unrestricted|unfiltered)\\b"
                    + "|<\\|[a-z_]+\\|>|\\[/?inst]|<</?sys>>"
                    + "|^\\s*#{0,3}\\s*(system|assistant)\\s*:",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    // Zero-width and other invisible format characters used to split keywords.
    private static final Pattern FORMAT_CHARACTERS = Pattern.compile("\\p{Cf}");

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
            if (isPromptAttack(prompt)) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Prompt rejected by safety policy"));
            }
            return Mono.empty();
        });
    }

    private static boolean isPromptAttack(String prompt) {
        String normalized = FORMAT_CHARACTERS
                .matcher(Normalizer.normalize(prompt, Normalizer.Form.NFKC))
                .replaceAll("");
        return PROMPT_ATTACK.matcher(normalized).find();
    }
}
