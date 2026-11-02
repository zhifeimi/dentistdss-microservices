package press.mizhifei.dentist.clinic.directory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClinicSlugsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // The contract example: the suburb is not repeated.
            "Smile Dental Parramatta | Parramatta    | smile-dental-parramatta",
            "Smile Dental            | Parramatta    | smile-dental-parramatta",
            "Parramatta Smile Dental | Parramatta    | parramatta-smile-dental",
            "Children's Dental       | North Sydney  | childrens-dental-north-sydney",
            "Smiles & Co.            | Fitzroy       | smiles-and-co-fitzroy",
            "Café Dentaire           | St Kilda      | cafe-dentaire-st-kilda",
            "  --Bright   Teeth--    | O'Connor      | bright-teeth-oconnor",
            // Matching is on whole words: "Parramatta" is not part of "Parramattas".
            "Parramattas Dental      | Parramatta    | parramattas-dental-parramatta",
    })
    void baseSlugCombinesNameAndSuburb(String name, String suburb, String expected) {
        String slug = ClinicSlugs.baseSlug(name, suburb);

        assertThat(slug).isEqualTo(expected);
        assertThat(ClinicSlugs.isValid(slug)).isTrue();
    }

    @Test
    void nameWithoutLatinLettersFallsBackToAGenericName() {
        assertThat(ClinicSlugs.baseSlug("微笑牙科", "Chatswood")).isEqualTo("dental-clinic-chatswood");
    }

    @Test
    void longNamesAreCutAtAWordBoundary() {
        String slug = ClinicSlugs.baseSlug("Comprehensive ".repeat(12) + "Dental", "Parramatta");

        assertThat(slug.length()).isLessThanOrEqualTo(ClinicSlugs.MAX_BASE_LENGTH);
        assertThat(slug).endsWith("comprehensive").doesNotEndWith("-");
        assertThat(ClinicSlugs.isValid(slug)).isTrue();
    }

    @Test
    void numberedCandidatesBelongToTheirBase() {
        assertThat(ClinicSlugs.candidate("smile-dental", 1)).isEqualTo("smile-dental");
        assertThat(ClinicSlugs.candidate("smile-dental", 3)).isEqualTo("smile-dental-3");

        assertThat(ClinicSlugs.isCandidateOf("smile-dental", "smile-dental")).isTrue();
        assertThat(ClinicSlugs.isCandidateOf("smile-dental-2", "smile-dental")).isTrue();
        assertThat(ClinicSlugs.isCandidateOf("smile-dental-12", "smile-dental")).isTrue();
        // "-1" is never generated, and other words are a different base.
        assertThat(ClinicSlugs.isCandidateOf("smile-dental-1", "smile-dental")).isFalse();
        assertThat(ClinicSlugs.isCandidateOf("smile-dental-parramatta", "smile-dental")).isFalse();
        assertThat(ClinicSlugs.isCandidateOf(null, "smile-dental")).isFalse();
    }

    @Test
    void validationMatchesTheDatabaseConstraint() {
        assertThat(ClinicSlugs.isValid("smile-dental-2")).isTrue();
        assertThat(ClinicSlugs.isValid("Smile-Dental")).isFalse();
        assertThat(ClinicSlugs.isValid("smile--dental")).isFalse();
        assertThat(ClinicSlugs.isValid("-smile")).isFalse();
        assertThat(ClinicSlugs.isValid("smile_dental")).isFalse();
        assertThat(ClinicSlugs.isValid("a".repeat(161))).isFalse();
        assertThat(ClinicSlugs.isValid("")).isFalse();
        assertThat(ClinicSlugs.isValid(null)).isFalse();
    }
}
