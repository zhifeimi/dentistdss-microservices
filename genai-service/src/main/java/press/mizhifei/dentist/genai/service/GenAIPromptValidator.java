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

    // Heuristic screening for known attack phrasings only. Novel phrasings
    // are expected to get through; ChatService's system prompt guardrail is
    // the backstop.
    private static final String INSTRUCTION_OVERRIDE =
            "\\b(ignore|disregard|forget|override|bypass)\\b.{0,40}"
                    + "\\b(previous|prior|above|earlier|system|your|all)\\s+"
                    + "(instructions?|rules|prompts?|directives|guidelines)\\b";
    private static final String PROMPT_EXFILTRATION =
            "\\b(reveal|show|print|repeat|output|leak)\\b.{0,40}"
                    + "\\b(system prompt|hidden instructions|initial instructions)\\b";
    private static final String JAILBREAK =
            "\\b(developer mode|jailbreak|do anything now)\\b"
                    + "|\\byou are now\\b.{0,20}\\b(dan|unrestricted|unfiltered)\\b";
    private static final String CHAT_TEMPLATE_TOKEN =
            "<\\|[a-z_]+\\|>|\\[/?inst]|<</?sys>>";
    // "system:" only; "Assistant:" is a normal line in dental clinical notes.
    private static final String SYSTEM_ROLE_PREFIX = "^\\s*#{0,3}\\s*system\\s*:";

    private static final Pattern PROMPT_ATTACK = Pattern.compile(
            String.join("|",
                    INSTRUCTION_OVERRIDE,
                    PROMPT_EXFILTRATION,
                    JAILBREAK,
                    CHAT_TEMPLATE_TOKEN,
                    SYSTEM_ROLE_PREFIX),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    // Invisible format characters and combining marks used to split or
    // decorate keywords.
    private static final Pattern OBFUSCATION_CHARACTERS =
            Pattern.compile("[\\p{Cf}\\p{M}]");

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
        String normalized = OBFUSCATION_CHARACTERS
                .matcher(Normalizer.normalize(prompt, Normalizer.Form.NFKD))
                .replaceAll("");
        return PROMPT_ATTACK.matcher(normalized).find();
    }
}
