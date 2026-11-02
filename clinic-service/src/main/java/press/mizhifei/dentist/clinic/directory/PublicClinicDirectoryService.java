package press.mizhifei.dentist.clinic.directory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.clinic.dto.OperatingHoursDto;
import press.mizhifei.dentist.clinic.dto.PublicClinicDirectoryResponse;
import press.mizhifei.dentist.clinic.dto.PublicClinicResponse;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.model.ClinicOperatingHours;
import press.mizhifei.dentist.clinic.model.ClinicSlugHistory;
import press.mizhifei.dentist.clinic.repository.ClinicOperatingHoursRepository;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.clinic.repository.ClinicSlugHistoryRepository;
import press.mizhifei.dentist.clinic.repository.ServiceRepository;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the public clinic directory served to the frontend's build-time
 * prerender. A clinic is listed only when it is approved, enabled, opted in
 * ({@code listedPublicly}), has a slug, a name, a suburb, and an Australian
 * state — the URL {@code /dentists/{state}/{suburb}/{slug}} needs all three.
 */
@Slf4j
@Service
public class PublicClinicDirectoryService {

    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter HOURS = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern BARE_HOST = Pattern.compile(
            "^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+(?:[/?#]\\S*)?$");
    /**
     * Private, fixed configuration: the fingerprint must depend only on the
     * payload, never on how the HTTP message converter happens to be set up.
     */
    private static final JsonMapper FINGERPRINT_MAPPER = JsonMapper.builder().build();

    private final ClinicRepository clinicRepository;
    private final ClinicOperatingHoursRepository operatingHoursRepository;
    private final ServiceRepository serviceRepository;
    private final ClinicSlugHistoryRepository slugHistoryRepository;
    private final Clock clock;
    private final ZoneId storedTimestampZone;

    @Autowired
    public PublicClinicDirectoryService(
            ClinicRepository clinicRepository,
            ClinicOperatingHoursRepository operatingHoursRepository,
            ServiceRepository serviceRepository,
            ClinicSlugHistoryRepository slugHistoryRepository,
            Clock clock) {
        this(clinicRepository, operatingHoursRepository, serviceRepository, slugHistoryRepository, clock,
                // clinics.created_at/updated_at are written with LocalDateTime.now().
                ZoneId.systemDefault());
    }

    PublicClinicDirectoryService(
            ClinicRepository clinicRepository,
            ClinicOperatingHoursRepository operatingHoursRepository,
            ServiceRepository serviceRepository,
            ClinicSlugHistoryRepository slugHistoryRepository,
            Clock clock,
            ZoneId storedTimestampZone) {
        this.clinicRepository = clinicRepository;
        this.operatingHoursRepository = operatingHoursRepository;
        this.serviceRepository = serviceRepository;
        this.slugHistoryRepository = slugHistoryRepository;
        this.clock = clock;
        this.storedTimestampZone = storedTimestampZone;
    }

    /** The full directory plus its fingerprint (the weak ETag and the rebuild-trigger key). */
    public record Directory(PublicClinicDirectoryResponse response, String fingerprint) {
    }

    /** Result of a slug lookup. */
    public sealed interface Lookup permits Found, MovedPermanently, NotFound {
    }

    public record Found(PublicClinicResponse clinic, String fingerprint) implements Lookup {
    }

    /** {@code slug} was retired; the clinic now lives at {@code currentSlug}. */
    public record MovedPermanently(String currentSlug) implements Lookup {
    }

    public record NotFound() implements Lookup {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Directory listClinics() {
        List<PublicClinicResponse> clinics = currentClinics();
        String generatedAt = ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.SECONDS));
        return new Directory(new PublicClinicDirectoryResponse(generatedAt, clinics), fingerprint(clinics));
    }

    /** Fingerprint of what {@link #listClinics()} would return now. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String currentFingerprint() {
        return fingerprint(currentClinics());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Lookup findBySlug(String slug) {
        if (!ClinicSlugs.isValid(slug)) {
            return new NotFound();
        }
        Optional<Clinic> listed = clinicRepository.findPubliclyListedBySlug(slug);
        if (listed.isPresent()) {
            Clinic clinic = listed.get();
            Optional<PublicClinicResponse> response = toPublic(
                    clinic,
                    operatingHoursRepository.findByClinicIdOrderByDayOfWeekAsc(clinic.getId()),
                    serviceRepository.findByClinicIdAndIsActiveTrue(clinic.getId()),
                    slugHistoryRepository.findByClinicIdOrderByRetiredAtDescSlugAsc(clinic.getId()));
            return response.<Lookup>map(value -> new Found(value, fingerprint(value)))
                    .orElseGet(NotFound::new);
        }
        // An old slug redirects only while its clinic is still listed, and
        // never reveals a clinic that is not.
        return slugHistoryRepository.findById(slug)
                .flatMap(previous -> clinicRepository.findById(previous.getClinicId()))
                .filter(clinic -> clinic.getSlug() != null)
                .flatMap(clinic -> clinicRepository.findPubliclyListedBySlug(clinic.getSlug()))
                .filter(clinic -> eligibilityProblem(clinic) == null)
                .<Lookup>map(clinic -> new MovedPermanently(clinic.getSlug()))
                .orElseGet(NotFound::new);
    }

    private List<PublicClinicResponse> currentClinics() {
        List<Clinic> clinics = clinicRepository.findPubliclyListed();
        Map<Long, List<ClinicOperatingHours>> hours = groupByClinic(
                operatingHoursRepository.findForPubliclyListedClinics(), ClinicOperatingHours::getClinicId);
        Map<Long, List<press.mizhifei.dentist.clinic.model.Service>> services = groupByClinic(
                serviceRepository.findActiveForPubliclyListedClinics(),
                press.mizhifei.dentist.clinic.model.Service::getClinicId);
        Map<Long, List<ClinicSlugHistory>> previousSlugs = groupByClinic(
                slugHistoryRepository.findForPubliclyListedClinics(), ClinicSlugHistory::getClinicId);

        List<PublicClinicResponse> result = new ArrayList<>(clinics.size());
        for (Clinic clinic : clinics) {
            toPublic(clinic,
                    hours.getOrDefault(clinic.getId(), List.of()),
                    services.getOrDefault(clinic.getId(), List.of()),
                    previousSlugs.getOrDefault(clinic.getId(), List.of()))
                    .ifPresent(result::add);
        }
        result.sort(Comparator.comparing(PublicClinicResponse::slug));
        return result;
    }

    private Optional<PublicClinicResponse> toPublic(
            Clinic clinic,
            List<ClinicOperatingHours> hours,
            List<press.mizhifei.dentist.clinic.model.Service> services,
            List<ClinicSlugHistory> previousSlugs) {
        String problem = eligibilityProblem(clinic);
        if (problem != null) {
            log.debug("Opted-in clinic {} is not in the public directory: {}", clinic.getId(), problem);
            return Optional.empty();
        }

        List<String> serviceNames = services.stream()
                .filter(service -> Boolean.TRUE.equals(service.getIsActive()))
                .map(press.mizhifei.dentist.clinic.model.Service::getName)
                .map(PublicClinicDirectoryService::trimToNull)
                .filter(name -> name != null)
                .collect(Collectors.toMap(
                        name -> name.toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                .toList();

        PublicClinicResponse.Location location = clinic.getLatitude() != null && clinic.getLongitude() != null
                ? new PublicClinicResponse.Location(clinic.getLatitude(), clinic.getLongitude())
                : null;

        return Optional.of(new PublicClinicResponse(
                clinic.getSlug(),
                previousSlugs.stream().map(ClinicSlugHistory::getSlug).toList(),
                clinic.getName().trim(),
                trimToNull(clinic.getDescription()),
                new PublicClinicResponse.Address(
                        trimToNull(clinic.getAddress()),
                        AustralianLocality.suburb(clinic.getCity()),
                        AustralianLocality.stateCode(clinic.getState()).orElseThrow(),
                        trimToNull(clinic.getZipCode()),
                        AustralianLocality.COUNTRY_CODE),
                location,
                trimToNull(clinic.getPhoneNumber()),
                publicWebsite(clinic.getWebsite()),
                hours.stream()
                        .sorted(Comparator.comparing(ClinicOperatingHours::getDayOfWeek))
                        .map(PublicClinicDirectoryService::toDto)
                        .toList(),
                serviceNames,
                // Patients book a service through DentistDSS; a clinic with no
                // active service has nothing to book online yet.
                !serviceNames.isEmpty(),
                updatedAt(clinic)));
    }

    /** Why an opted-in clinic cannot be listed, or {@code null} when it can. */
    private static String eligibilityProblem(Clinic clinic) {
        if (!ClinicSlugs.isValid(clinic.getSlug())) {
            return "no valid slug";
        }
        if (trimToNull(clinic.getName()) == null) {
            return "no name";
        }
        if (AustralianLocality.suburb(clinic.getCity()) == null) {
            return "no suburb";
        }
        if (!AustralianLocality.isAustralia(clinic.getCountry())) {
            return "country is not Australia";
        }
        if (AustralianLocality.stateCode(clinic.getState()).isEmpty()) {
            return "state is not an Australian state or territory";
        }
        return null;
    }

    public static OperatingHoursDto toDto(ClinicOperatingHours hours) {
        return new OperatingHoursDto(
                hours.getDayOfWeek(),
                format(hours.getOpenTime()),
                format(hours.getCloseTime()),
                Boolean.TRUE.equals(hours.getIsOpen()));
    }

    private static String format(LocalTime time) {
        return time == null ? null : HOURS.format(time);
    }

    private String updatedAt(Clinic clinic) {
        LocalDateTime stored = clinic.getUpdatedAt() != null ? clinic.getUpdatedAt() : clinic.getCreatedAt();
        if (stored == null) {
            return null;
        }
        Instant instant = stored.atZone(storedTimestampZone).toInstant().truncatedTo(ChronoUnit.SECONDS);
        return ISO_INSTANT.format(instant);
    }

    /**
     * Only an absolute http(s) URL reaches the page (no {@code javascript:}
     * or other schemes in an href). A bare host such as
     * {@code www.smile.example} gets {@code https://}.
     */
    static String publicWebsite(String website) {
        String value = trimToNull(website);
        if (value == null) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (!BARE_HOST.matcher(value).matches()) {
                return null;
            }
            value = "https://" + value;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                return null;
            }
            return value;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static <T> Map<Long, List<T>> groupByClinic(List<T> rows, Function<T, Long> clinicId) {
        return rows.stream().collect(Collectors.groupingBy(clinicId, LinkedHashMap::new, Collectors.toList()));
    }

    static String fingerprint(Object payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(FINGERPRINT_MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
