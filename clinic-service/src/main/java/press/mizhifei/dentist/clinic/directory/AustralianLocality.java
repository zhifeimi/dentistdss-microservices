package press.mizhifei.dentist.clinic.directory;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Normalises the free-text address fields clinics enter at registration into
 * the values the public directory contract promises: an Australian state or
 * territory code, the country code {@code AU}, and a consistently capitalised
 * suburb.
 */
public final class AustralianLocality {

    public static final String COUNTRY_CODE = "AU";

    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("nsw", "NSW"), Map.entry("new south wales", "NSW"),
            Map.entry("vic", "VIC"), Map.entry("victoria", "VIC"),
            Map.entry("qld", "QLD"), Map.entry("queensland", "QLD"),
            Map.entry("wa", "WA"), Map.entry("western australia", "WA"),
            Map.entry("sa", "SA"), Map.entry("south australia", "SA"),
            Map.entry("tas", "TAS"), Map.entry("tasmania", "TAS"),
            Map.entry("act", "ACT"), Map.entry("australian capital territory", "ACT"),
            Map.entry("nt", "NT"), Map.entry("northern territory", "NT"));

    private AustralianLocality() {
    }

    /** {@code NSW}, {@code VIC}, ... for a code or full name in any case ("N.S.W." too). */
    public static Optional<String> stateCode(String state) {
        if (state == null) {
            return Optional.empty();
        }
        String key = collapseWhitespace(state.replace(".", "")).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(STATES.get(key));
    }

    /**
     * Whether the country field means Australia. A blank country is accepted:
     * the state must still be an Australian state or territory.
     */
    public static boolean isAustralia(String country) {
        if (country == null || country.isBlank()) {
            return true;
        }
        String key = collapseWhitespace(country.replace(".", "")).toLowerCase(Locale.ROOT);
        return key.equals("au") || key.equals("aus") || key.equals("australia")
                || key.equals("commonwealth of australia");
    }

    /**
     * The suburb with consistent capitalisation. All-lowercase or all-capitals
     * input is title-cased ("NORTH SYDNEY" -> "North Sydney",
     * "o'connor" -> "O'Connor"); mixed case is kept as entered, because it is
     * usually deliberate ("McMahons Point").
     */
    public static String suburb(String suburb) {
        if (suburb == null) {
            return null;
        }
        String value = collapseWhitespace(suburb);
        if (value.isEmpty()) {
            return null;
        }
        boolean allLower = value.equals(value.toLowerCase(Locale.ROOT));
        boolean allUpper = value.equals(value.toUpperCase(Locale.ROOT));
        if (!allLower && !allUpper) {
            return value;
        }
        StringBuilder result = new StringBuilder(value.length());
        boolean startOfWord = true;
        for (char character : value.toLowerCase(Locale.ROOT).toCharArray()) {
            result.append(startOfWord ? Character.toUpperCase(character) : character);
            startOfWord = character == ' ' || character == '-' || character == '\'';
        }
        return result.toString();
    }

    private static String collapseWhitespace(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
