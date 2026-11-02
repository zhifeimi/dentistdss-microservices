package press.mizhifei.dentist.clinic.directory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import press.mizhifei.dentist.clinic.dto.PublicClinicResponse;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.model.Service;
import press.mizhifei.dentist.clinic.repository.ClinicOperatingHoursRepository;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.clinic.repository.ClinicSlugHistoryRepository;
import press.mizhifei.dentist.clinic.repository.ServiceRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicClinicDirectoryServiceTest {

    private ClinicRepository clinicRepository;
    private ServiceRepository serviceRepository;
    private PublicClinicDirectoryService service;

    @BeforeEach
    void setUp() {
        clinicRepository = mock(ClinicRepository.class);
        serviceRepository = mock(ServiceRepository.class);
        ClinicOperatingHoursRepository hoursRepository = mock(ClinicOperatingHoursRepository.class);
        ClinicSlugHistoryRepository slugHistoryRepository = mock(ClinicSlugHistoryRepository.class);
        when(hoursRepository.findForPubliclyListedClinics()).thenReturn(List.of());
        when(slugHistoryRepository.findForPubliclyListedClinics()).thenReturn(List.of());
        when(serviceRepository.findActiveForPubliclyListedClinics()).thenReturn(List.of());
        service = new PublicClinicDirectoryService(clinicRepository, hoursRepository, serviceRepository,
                slugHistoryRepository, Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC),
                ZoneId.of("Australia/Sydney"));
    }

    @Test
    void onlyClinicsThatCanHaveAPublicUrlAreListed() {
        when(clinicRepository.findPubliclyListed()).thenReturn(List.of(
                clinic(1L, "b-dental", c -> { }),
                clinic(2L, "foreign-dental", c -> c.setCountry("New Zealand")),
                clinic(3L, "nostate-dental", c -> c.setState("Ontario")),
                clinic(4L, "nosuburb-dental", c -> c.setCity("  ")),
                clinic(5L, "noname-dental", c -> c.setName(" ")),
                clinic(6L, "Bad_Slug", c -> { }),
                clinic(7L, "a-dental", c -> c.setCountry(null))));

        List<PublicClinicResponse> clinics = service.listClinics().response().clinics();

        // Sorted by slug; a blank country still means Australia when the state is Australian.
        assertThat(clinics).extracting(PublicClinicResponse::slug).containsExactly("a-dental", "b-dental");
    }

    @Test
    void servicesAreTrimmedDedupedAndSortedAndDriveOnlineBooking() {
        when(clinicRepository.findPubliclyListed()).thenReturn(List.of(
                clinic(1L, "with-services", c -> { }),
                clinic(2L, "without-services", c -> { })));
        when(serviceRepository.findActiveForPubliclyListedClinics()).thenReturn(List.of(
                service(1L, " Whitening "), service(1L, "fillings"), service(1L, "Fillings"),
                service(1L, "  "), service(1L, "Check-up and clean")));

        List<PublicClinicResponse> clinics = service.listClinics().response().clinics();

        assertThat(clinics.get(0).services()).containsExactly("Check-up and clean", "fillings", "Whitening");
        assertThat(clinics.get(0).acceptsOnlineBooking()).isTrue();
        assertThat(clinics.get(1).services()).isEmpty();
        assertThat(clinics.get(1).acceptsOnlineBooking()).isFalse();
    }

    @Test
    void locationNeedsBothCoordinatesAndTimesAreUtcSeconds() {
        when(clinicRepository.findPubliclyListed()).thenReturn(List.of(clinic(1L, "half-located", c -> {
            c.setLatitude(-33.8);
            c.setLongitude(null);
            c.setUpdatedAt(LocalDateTime.of(2026, 10, 30, 15, 12, 9, 999_000_000));
        })));

        PublicClinicResponse clinic = service.listClinics().response().clinics().get(0);

        assertThat(clinic.location()).isNull();
        // 15:12 in Sydney (AEDT, UTC+11) is 04:12 UTC.
        assertThat(clinic.updatedAt()).isEqualTo("2026-10-30T04:12:09Z");
        assertThat(service.listClinics().response().generatedAt()).isEqualTo("2026-11-02T00:00:00Z");
    }

    @Test
    void fingerprintIgnoresGeneratedAtButFollowsTheData() {
        Clinic clinic = clinic(1L, "smile-dental", c -> { });
        when(clinicRepository.findPubliclyListed()).thenReturn(List.of(clinic));

        String first = service.listClinics().fingerprint();
        assertThat(service.currentFingerprint()).isEqualTo(first).hasSize(64);

        clinic.setPhoneNumber("02 9999 9999");
        assertThat(service.currentFingerprint()).isNotEqualTo(first);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "https://smile.example/book    | https://smile.example/book",
            "HTTP://smile.example          | HTTP://smile.example",
            "www.smile.example.com.au      | https://www.smile.example.com.au",
            "smile.example/contact?x=1     | https://smile.example/contact?x=1",
            "javascript:alert(1)           | null",
            "ftp://smile.example           | null",
            "https://user:pw@smile.example | null",
            "not a website                 | null",
            "localhost                     | null",
    })
    void onlyAbsoluteHttpWebsitesReachThePage(String entered, String expected) {
        assertThat(PublicClinicDirectoryService.publicWebsite(entered)).isEqualTo(expected);
    }

    private static Clinic clinic(Long id, String slug, Consumer<Clinic> customizer) {
        Clinic clinic = Clinic.builder()
                .id(id)
                .name("Dental " + id)
                .address("1 Main Street")
                .city("Parramatta")
                .state("NSW")
                .zipCode("2150")
                .country("AU")
                .slug(slug)
                .listedPublicly(true)
                .approved(true)
                .enabled(true)
                .updatedAt(LocalDateTime.of(2026, 10, 30, 0, 0))
                .build();
        customizer.accept(clinic);
        return clinic;
    }

    private static Service service(Long clinicId, String name) {
        return Service.builder().clinicId(clinicId).name(name).isActive(true).build();
    }
}
