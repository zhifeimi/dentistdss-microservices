package press.mizhifei.dentist.auth.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyCodeRequestValidationTest {

    @Test
    void acceptsStrongMailboxSelectedPassword() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            VerifyCodeRequest request = new VerifyCodeRequest(
                    "patient@example.com",
                    "123456",
                    "FinalStrong1!");

            assertTrue(validator.validate(request).isEmpty());
        }
    }

    @Test
    void rejectsWeakMailboxSelectedPassword() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            VerifyCodeRequest request = new VerifyCodeRequest(
                    "patient@example.com",
                    "123456",
                    "weakpass");

            assertFalse(validator.validate(request).isEmpty());
            assertTrue(validator.validate(request).stream()
                    .anyMatch(violation -> "newPassword".equals(
                            violation.getPropertyPath().toString())));
        }
    }
}
