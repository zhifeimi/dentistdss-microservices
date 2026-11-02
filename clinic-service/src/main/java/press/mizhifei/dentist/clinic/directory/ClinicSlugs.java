package press.mizhifei.dentist.clinic.directory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure slug rules for public clinic URLs
 * ({@code /dentists/{state}/{suburb}/{slug}}): lowercase ASCII words joined
 * by single hyphens, built from the clinic name and suburb.
 */
public final class ClinicSlugs {

    /** Matches the {@code ck_clinics_slug_format} database constraint. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern NUMBERED = Pattern.compile("-([2-9]|[1-9][0-9]+)$");
    static final int MAX_LENGTH = 160;
    /** Leaves room for a "-NNN" uniqueness suffix well inside {@link #MAX_LENGTH}. */
    static final int MAX_BASE_LENGTH = 96;
    private static final String FALLBACK_NAME = "dental-clinic";

    private ClinicSlugs() {
    }

    public static boolean isValid(String slug) {
        return slug != null && slug.length() <= MAX_LENGTH && SLUG.matcher(slug).matches();
    }

    /** Lowercase, ASCII, hyphen-separated form of free text ("" when nothing survives). */
    public static String slugify(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace("ß", "ss").replace("æ", "ae").replace("Æ", "AE")
                .replace("ø", "o").replace("Ø", "O").replace("đ", "d").replace("Đ", "D")
                .replace("ł", "l").replace("Ł", "L");
        value = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        value = value.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                // "Children's" -> "childrens", not "children-s".
                .replaceAll("['‘’`]", "")
                .replaceAll("[^a-z0-9]+", "-");
        return trimHyphens(value);
    }

    /**
     * The preferred slug for a clinic: its name plus its suburb, without
     * repeating the suburb when the name already contains it
     * ("Smile Dental Parramatta" in Parramatta -> "smile-dental-parramatta").
     */
    public static String baseSlug(String name, String suburb) {
        String namePart = slugify(name);
        if (namePart.isEmpty()) {
            // e.g. a name written only in non-Latin script.
            namePart = FALLBACK_NAME;
        }
        String suburbPart = slugify(suburb);
        String base = suburbPart.isEmpty() || containsWords(namePart, suburbPart)
                ? namePart
                : namePart + "-" + suburbPart;
        return truncate(base, MAX_BASE_LENGTH);
    }

    /** The n-th candidate for a base: {@code base}, {@code base-2}, {@code base-3}, ... */
    public static String candidate(String base, int attempt) {
        return attempt <= 1 ? base : base + "-" + attempt;
    }

    /**
     * Whether {@code slug} is {@code base} or one of its numbered candidates,
     * so a clinic keeps a collision-suffixed slug when its base did not change.
     */
    public static boolean isCandidateOf(String slug, String base) {
        if (slug == null || base == null) {
            return false;
        }
        if (slug.equals(base)) {
            return true;
        }
        return slug.startsWith(base + "-")
                && NUMBERED.matcher(slug.substring(base.length())).matches();
    }

    private static boolean containsWords(String slug, String words) {
        return ("-" + slug + "-").contains("-" + words + "-");
    }

    private static String truncate(String slug, int maxLength) {
        if (slug.length() <= maxLength) {
            return slug;
        }
        String cut = slug.substring(0, maxLength);
        int lastHyphen = cut.lastIndexOf('-');
        // Cut at a word boundary rather than mid-word.
        if (lastHyphen > 0) {
            cut = cut.substring(0, lastHyphen);
        }
        return trimHyphens(cut);
    }

    private static String trimHyphens(String value) {
        return value.replaceAll("^-+", "").replaceAll("-+$", "");
    }
}
