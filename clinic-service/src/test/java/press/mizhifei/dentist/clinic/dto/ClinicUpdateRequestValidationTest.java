package press.mizhifei.dentist.clinic.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clinic settings form sends {@code UpdateClinicRequest} from the
 * frontend's {@code src/types/api.ts}; these are the rules the new public
 * fields (hours, description, website, location, listing) must satisfy.
 */
class ClinicUpdateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void frontendShapedRequestBindsAndIsValid() {
        ClinicUpdateRequest request = mapper.readValue("""
                {"description":"Family clinic","website":"https://smile.example","listedPublicly":true,
                 "latitude":-33.815,"longitude":151.0011,
                 "operatingHours":[{"dayOfWeek":1,"openTime":"08:30","closeTime":"17:30","isOpen":true},
                                   {"dayOfWeek":0,"openTime":"09:00","closeTime":"12:00","isOpen":false}]}
                """, ClinicUpdateRequest.class);

        assertThat(violations(request)).isEmpty();
        assertThat(request.getListedPublicly()).isTrue();
        assertThat(request.getOperatingHours()).hasSize(2);
        assertThat(request.getOperatingHours().get(0).isOpen()).isTrue();
        assertThat(request.getOperatingHours().get(1).isOpen()).isFalse();
    }

    @Test
    void repeatedDayIsRejected() {
        ClinicUpdateRequest request = new ClinicUpdateRequest();
        request.setOperatingHours(List.of(open(1, "08:30", "17:30"), open(1, "09:00", "12:00")));

        assertThat(violations(request)).containsExactly("Operating hours must not repeat a day");
    }

    @Test
    void openDayMustOpenBeforeItCloses() {
        ClinicUpdateRequest request = new ClinicUpdateRequest();
        request.setOperatingHours(List.of(open(2, "17:30", "08:30")));

        assertThat(violations(request)).containsExactly("openTime must be before closeTime on an open day");
    }

    @Test
    void closedDayMayCarryAnyTimes() {
        ClinicUpdateRequest request = new ClinicUpdateRequest();
        request.setOperatingHours(List.of(new OperatingHoursDto(0, "00:00", "00:00", false)));

        assertThat(violations(request)).isEmpty();
    }

    @Test
    void malformedHoursAreRejected() {
        ClinicUpdateRequest request = new ClinicUpdateRequest();
        request.setOperatingHours(List.of(
                new OperatingHoursDto(7, "8:30", "24:00", null)));

        assertThat(violations(request)).containsExactlyInAnyOrder(
                "dayOfWeek must be 0 (Sunday) to 6 (Saturday)",
                "openTime must be HH:mm",
                "closeTime must be HH:mm",
                "isOpen is required");
    }

    @Test
    void locationNeedsBothCoordinatesInRange() {
        ClinicUpdateRequest halfLocated = new ClinicUpdateRequest();
        halfLocated.setLatitude(-33.8);
        assertThat(violations(halfLocated)).containsExactly("Latitude and longitude must be set together");

        ClinicUpdateRequest outOfRange = new ClinicUpdateRequest();
        outOfRange.setLatitude(-91.0);
        outOfRange.setLongitude(181.0);
        assertThat(violations(outOfRange)).containsExactlyInAnyOrder(
                "Latitude must be between -90 and 90", "Longitude must be between -180 and 180");
    }

    @Test
    void websiteMustBeHttpOrBlank() {
        ClinicUpdateRequest request = new ClinicUpdateRequest();
        request.setWebsite("javascript:alert(1)");
        assertThat(violations(request)).containsExactly("Website must start with http:// or https://");

        request.setWebsite("");
        assertThat(violations(request)).isEmpty();
        request.setWebsite("HTTPS://Smile.example/book");
        assertThat(violations(request)).isEmpty();
    }

    @Test
    void longDescriptionIsRejected() {
        ClinicUpdateRequest request = new ClinicUpdateRequest();
        request.setDescription("x".repeat(2001));

        assertThat(violations(request)).containsExactly("Description must not exceed 2000 characters");
    }

    private static OperatingHoursDto open(int day, String openTime, String closeTime) {
        return new OperatingHoursDto(day, openTime, closeTime, true);
    }

    private static List<String> violations(ClinicUpdateRequest request) {
        Set<ConstraintViolation<ClinicUpdateRequest>> violations = validator.validate(request);
        return violations.stream().map(ConstraintViolation::getMessage).sorted().collect(Collectors.toList());
    }
}
