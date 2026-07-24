package press.mizhifei.dentist.genai.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenAIPromptValidatorTest {

    private final GenAIPromptValidator validator =
            new GenAIPromptValidator(8);

    @Test
    void acceptsPromptAtConfiguredLimit() {
        StepVerifier.create(validator.validate("12345678"))
                .verifyComplete();
    }

    @Test
    void rejectsNullAndWhitespaceOnlyPrompts() {
        assertStatus(validator.validate(null), HttpStatus.BAD_REQUEST);
        assertStatus(validator.validate(" \n\t "), HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsPromptAboveConfiguredLimit() {
        assertStatus(
                validator.validate("123456789"),
                HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void rejectsInvalidConfiguredLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new GenAIPromptValidator(0));
    }

    private void assertStatus(
            reactor.core.publisher.Mono<Void> validation,
            HttpStatus expectedStatus) {
        StepVerifier.create(validation)
                .expectErrorSatisfies(error -> {
                    ResponseStatusException statusException =
                            (ResponseStatusException) error;
                    assertEquals(expectedStatus,
                            statusException.getStatusCode());
                })
                .verify();
    }
}
