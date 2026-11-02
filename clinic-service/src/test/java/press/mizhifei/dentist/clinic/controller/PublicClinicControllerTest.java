package press.mizhifei.dentist.clinic.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import press.mizhifei.dentist.clinic.config.ClinicSecurityConfig;
import press.mizhifei.dentist.clinic.directory.PublicClinicDirectoryService;
import press.mizhifei.dentist.clinic.directory.PublicDirectoryProperties;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.model.ClinicOperatingHours;
import press.mizhifei.dentist.clinic.model.ClinicSlugHistory;
import press.mizhifei.dentist.clinic.model.Service;
import press.mizhifei.dentist.clinic.repository.ClinicOperatingHoursRepository;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.clinic.repository.ClinicSlugHistoryRepository;
import press.mizhifei.dentist.clinic.repository.ServiceRepository;
import press.mizhifei.dentist.security.RedisAccessTokenJwtDecoder;
import press.mizhifei.dentist.security.ServletBearerTokenFailureHandler;
import press.mizhifei.dentist.security.ServletJwtResourceServerCustomizer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        PublicClinicControllerTest.TestConfig.class,
        ClinicSecurityConfig.class
})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class PublicClinicControllerTest {

    @Autowired
    private WebApplicationContext applicationContext;
    @Autowired
    private ClinicRepository clinicRepository;
    @Autowired
    private ClinicOperatingHoursRepository hoursRepository;
    @Autowired
    private ServiceRepository serviceRepository;
    @Autowired
    private ClinicSlugHistoryRepository slugHistoryRepository;
    @Autowired
    private MutableClock clock;

    private MockMvc mockMvc;
    private Clinic clinic;

    @BeforeEach
    void setUp() {
        reset(clinicRepository, hoursRepository, serviceRepository, slugHistoryRepository);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        clinic = Clinic.builder()
                .id(7L)
                .name("Smile Dental Parramatta")
                .description("Family dental clinic near Parramatta station.")
                .address("123 Church Street")
                .city("parramatta")
                .state("New South Wales")
                .zipCode("2150")
                .country("Australia")
                .phoneNumber("+61 2 9000 0000")
                .email("frontdesk@smiledental.example")
                .website("https://smiledental.example")
                .slug("smile-dental-parramatta")
                .listedPublicly(true)
                .approved(true)
                .enabled(true)
                .approvalBy(1L)
                .latitude(-33.8150)
                .longitude(151.0011)
                .updatedAt(LocalDateTime.of(2026, 10, 30, 4, 12, 0, 123_000_000))
                .build();
        ClinicOperatingHours monday = ClinicOperatingHours.builder()
                .id(1L).clinicId(7L).dayOfWeek(1)
                .openTime(LocalTime.of(8, 30)).closeTime(LocalTime.of(17, 30)).isOpen(true).build();
        ClinicOperatingHours sunday = ClinicOperatingHours.builder()
                .id(2L).clinicId(7L).dayOfWeek(0)
                .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(12, 0)).isOpen(false).build();
        Service checkUp = Service.builder().id(3).clinicId(7L).name("Check-up and clean").isActive(true).build();
        Service fillings = Service.builder().id(4).clinicId(7L).name("Fillings").isActive(true).build();
        ClinicSlugHistory previous = ClinicSlugHistory.builder()
                .slug("smile-dental").clinicId(7L).retiredAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();

        when(clinicRepository.findPubliclyListed()).thenReturn(List.of(clinic));
        when(hoursRepository.findForPubliclyListedClinics()).thenReturn(List.of(sunday, monday));
        when(serviceRepository.findActiveForPubliclyListedClinics()).thenReturn(List.of(fillings, checkUp));
        when(slugHistoryRepository.findForPubliclyListedClinics()).thenReturn(List.of(previous));

        when(clinicRepository.findPubliclyListedBySlug(anyString())).thenReturn(Optional.empty());
        when(clinicRepository.findPubliclyListedBySlug("smile-dental-parramatta")).thenReturn(Optional.of(clinic));
        when(clinicRepository.findById(7L)).thenReturn(Optional.of(clinic));
        when(hoursRepository.findByClinicIdOrderByDayOfWeekAsc(7L)).thenReturn(List.of(sunday, monday));
        when(serviceRepository.findByClinicIdAndIsActiveTrue(7L)).thenReturn(List.of(fillings, checkUp));
        when(slugHistoryRepository.findByClinicIdOrderByRetiredAtDescSlugAsc(7L)).thenReturn(List.of(previous));
        when(slugHistoryRepository.findById(anyString())).thenReturn(Optional.empty());
        when(slugHistoryRepository.findById("smile-dental")).thenReturn(Optional.of(previous));
    }

    @Test
    void directoryIsAnonymousCacheableAndMatchesTheContract() throws Exception {
        mockMvc.perform(get("/public/clinics"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
                .andExpect(header().string(HttpHeaders.ETAG, startsWith("W/\"")))
                .andExpect(jsonPath("$.generatedAt").value("2026-11-02T00:00:00Z"))
                .andExpect(jsonPath("$.clinics", hasSize(1)))
                .andExpect(jsonPath("$.clinics[0].slug").value("smile-dental-parramatta"))
                .andExpect(jsonPath("$.clinics[0].previousSlugs[0]").value("smile-dental"))
                .andExpect(jsonPath("$.clinics[0].name").value("Smile Dental Parramatta"))
                .andExpect(jsonPath("$.clinics[0].description").value("Family dental clinic near Parramatta station."))
                .andExpect(jsonPath("$.clinics[0].address.street").value("123 Church Street"))
                .andExpect(jsonPath("$.clinics[0].address.suburb").value("Parramatta"))
                .andExpect(jsonPath("$.clinics[0].address.state").value("NSW"))
                .andExpect(jsonPath("$.clinics[0].address.postcode").value("2150"))
                .andExpect(jsonPath("$.clinics[0].address.country").value("AU"))
                .andExpect(jsonPath("$.clinics[0].location.latitude").value(-33.8150))
                .andExpect(jsonPath("$.clinics[0].location.longitude").value(151.0011))
                .andExpect(jsonPath("$.clinics[0].phone").value("+61 2 9000 0000"))
                .andExpect(jsonPath("$.clinics[0].website").value("https://smiledental.example"))
                // Sorted by day; closed days keep their times; "isOpen", not "open".
                .andExpect(jsonPath("$.clinics[0].operatingHours[0].dayOfWeek").value(0))
                .andExpect(jsonPath("$.clinics[0].operatingHours[0].isOpen").value(false))
                .andExpect(jsonPath("$.clinics[0].operatingHours[1].dayOfWeek").value(1))
                .andExpect(jsonPath("$.clinics[0].operatingHours[1].openTime").value("08:30"))
                .andExpect(jsonPath("$.clinics[0].operatingHours[1].closeTime").value("17:30"))
                .andExpect(jsonPath("$.clinics[0].operatingHours[1].isOpen").value(true))
                .andExpect(jsonPath("$.clinics[0].operatingHours[1].open").doesNotExist())
                .andExpect(jsonPath("$.clinics[0].operatingHours[1].timeRangeValid").doesNotExist())
                .andExpect(jsonPath("$.clinics[0].services[0]").value("Check-up and clean"))
                .andExpect(jsonPath("$.clinics[0].services[1]").value("Fillings"))
                .andExpect(jsonPath("$.clinics[0].acceptsOnlineBooking").value(true))
                // Stored LocalDateTime is server-local; output is UTC, whole seconds.
                .andExpect(jsonPath("$.clinics[0].updatedAt").value(java.time.format.DateTimeFormatter.ISO_INSTANT
                        .format(LocalDateTime.of(2026, 10, 30, 4, 12).atZone(ZoneId.systemDefault()).toInstant())))
                // Public fields only.
                .andExpect(jsonPath("$.clinics[0].id").doesNotExist())
                .andExpect(jsonPath("$.clinics[0].email").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("frontdesk@smiledental.example"))));
    }

    @Test
    void matchingIfNoneMatchIsNotModifiedEvenAfterGeneratedAtMoves() throws Exception {
        String etag = mockMvc.perform(get("/public/clinics"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        clock.advance(Duration.ofMinutes(3));
        mockMvc.perform(get("/public/clinics").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
                .andExpect(content().string(""));
    }

    @Test
    void etagChangesWhenPublicDataChanges() throws Exception {
        String before = mockMvc.perform(get("/public/clinics"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        clinic.setDescription("Now open on Saturdays.");
        MvcResult after = mockMvc.perform(get("/public/clinics").header(HttpHeaders.IF_NONE_MATCH, before))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(after.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(before);
    }

    @Test
    void singleClinicHasTheSameShape() throws Exception {
        mockMvc.perform(get("/public/clinics/{slug}", "smile-dental-parramatta"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
                .andExpect(header().string(HttpHeaders.ETAG, startsWith("W/\"")))
                .andExpect(jsonPath("$.slug").value("smile-dental-parramatta"))
                .andExpect(jsonPath("$.address.state").value("NSW"))
                .andExpect(jsonPath("$.operatingHours", hasSize(2)))
                .andExpect(jsonPath("$.services", hasSize(2)))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void oldSlugRedirectsPermanentlyToTheCurrentOne() throws Exception {
        mockMvc.perform(get("/public/clinics/{slug}", "smile-dental"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/public/clinics/smile-dental-parramatta"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"));
    }

    @Test
    void oldSlugOfAClinicThatIsNoLongerListedIsNotFound() throws Exception {
        when(clinicRepository.findPubliclyListedBySlug("smile-dental-parramatta")).thenReturn(Optional.empty());

        mockMvc.perform(get("/public/clinics/{slug}", "smile-dental"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void unknownSlugIsNotFound() throws Exception {
        mockMvc.perform(get("/public/clinics/{slug}", "no-such-clinic"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Clinic not found"));
    }

    @Test
    void malformedSlugIsNotFoundWithoutTouchingTheDatabase() throws Exception {
        mockMvc.perform(get("/public/clinics/{slug}", "Smile_Dental"))
                .andExpect(status().isNotFound());

        verify(clinicRepository, never()).findPubliclyListedBySlug(anyString());
        verify(slugHistoryRepository, never()).findById(anyString());
    }

    @Test
    void ineligibleClinicIsLeftOutOfTheDirectory() throws Exception {
        clinic.setState("Ontario");

        mockMvc.perform(get("/public/clinics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clinics", hasSize(0)));
        mockMvc.perform(get("/public/clinics/{slug}", "smile-dental-parramatta"))
                .andExpect(status().isNotFound());
    }

    @Test
    void directoryIsReadOnly() throws Exception {
        // Only GET is open: an anonymous write is refused before any handler
        // runs (CSRF answers first), and deeper paths are not part of it.
        mockMvc.perform(post("/public/clinics").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/public/clinics/{slug}/anything", "smile-dental-parramatta"))
                .andExpect(status().isUnauthorized());

        verify(clinicRepository, never()).findPubliclyListed();
    }

    /** A clock the test can move forward. */
    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-02T00:00:00.456Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        ClinicRepository clinicRepository() {
            return mock(ClinicRepository.class);
        }

        @Bean
        ClinicOperatingHoursRepository clinicOperatingHoursRepository() {
            return mock(ClinicOperatingHoursRepository.class);
        }

        @Bean
        ServiceRepository serviceRepository() {
            return mock(ServiceRepository.class);
        }

        @Bean
        ClinicSlugHistoryRepository clinicSlugHistoryRepository() {
            return mock(ClinicSlugHistoryRepository.class);
        }

        @Bean
        MutableClock clock() {
            return new MutableClock();
        }

        @Bean
        PublicDirectoryProperties publicDirectoryProperties() {
            return new PublicDirectoryProperties(
                    "/api/public/clinics",
                    Duration.ofMinutes(5),
                    new PublicDirectoryProperties.Sync(false, Duration.ofMinutes(5), Duration.ofMinutes(1)),
                    new PublicDirectoryProperties.DeployHook("", Duration.ofHours(1), Duration.ofMinutes(10),
                            Duration.ofSeconds(5), Duration.ofSeconds(10)));
        }

        @Bean
        PublicClinicDirectoryService publicClinicDirectoryService(
                ClinicRepository clinicRepository,
                ClinicOperatingHoursRepository hoursRepository,
                ServiceRepository serviceRepository,
                ClinicSlugHistoryRepository slugHistoryRepository,
                MutableClock clock) {
            return new PublicClinicDirectoryService(
                    clinicRepository, hoursRepository, serviceRepository, slugHistoryRepository, clock);
        }

        @Bean
        PublicClinicController publicClinicController(
                PublicClinicDirectoryService service,
                PublicDirectoryProperties properties) {
            return new PublicClinicController(service, properties);
        }

        @Bean
        ServletJwtResourceServerCustomizer servletJwtResourceServerCustomizer() {
            return new ServletJwtResourceServerCustomizer(
                    mock(RedisAccessTokenJwtDecoder.class),
                    new JwtAuthenticationConverter(),
                    new ServletBearerTokenFailureHandler());
        }
    }
}
