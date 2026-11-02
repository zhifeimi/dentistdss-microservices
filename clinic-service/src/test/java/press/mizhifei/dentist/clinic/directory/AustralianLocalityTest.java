package press.mizhifei.dentist.clinic.directory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AustralianLocalityTest {

    @ParameterizedTest
    @CsvSource({
            "NSW,NSW", "nsw,NSW", "N.S.W.,NSW", "New South Wales,NSW", "  new   south wales ,NSW",
            "Vic,VIC", "Victoria,VIC", "QLD,QLD", "Queensland,QLD", "WA,WA", "Western Australia,WA",
            "SA,SA", "South Australia,SA", "Tas,TAS", "Tasmania,TAS", "ACT,ACT",
            "Australian Capital Territory,ACT", "NT,NT", "Northern Territory,NT"
    })
    void recognisesEveryStateAndTerritory(String entered, String code) {
        assertThat(AustralianLocality.stateCode(entered)).contains(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "California", "Ontario", "Sydney", "NSWW"})
    void rejectsAnythingElseAsAState(String entered) {
        assertThat(AustralianLocality.stateCode(entered)).isEmpty();
    }

    @Test
    void countryMeansAustraliaOrIsBlank() {
        assertThat(AustralianLocality.isAustralia("AU")).isTrue();
        assertThat(AustralianLocality.isAustralia("australia")).isTrue();
        assertThat(AustralianLocality.isAustralia("AUS")).isTrue();
        assertThat(AustralianLocality.isAustralia(null)).isTrue();
        assertThat(AustralianLocality.isAustralia("NZ")).isFalse();
        assertThat(AustralianLocality.isAustralia("United States")).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "parramatta       | Parramatta",
            "NORTH SYDNEY     | North Sydney",
            "  north   sydney | North Sydney",
            "o'connor         | O'Connor",
            // Mixed case is deliberate and kept.
            "McMahons Point   | McMahons Point",
            "St Kilda East    | St Kilda East",
    })
    void suburbsGetConsistentCapitalisation(String entered, String expected) {
        assertThat(AustralianLocality.suburb(entered)).isEqualTo(expected);
    }

    @Test
    void blankSuburbIsMissing() {
        assertThat(AustralianLocality.suburb("   ")).isNull();
        assertThat(AustralianLocality.suburb(null)).isNull();
    }
}
