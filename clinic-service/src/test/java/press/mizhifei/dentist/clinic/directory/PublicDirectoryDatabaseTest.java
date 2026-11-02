package press.mizhifei.dentist.clinic.directory;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import press.mizhifei.dentist.clinic.directory.PublicDirectorySyncService.Outcome;
import press.mizhifei.dentist.clinic.dto.PublicClinicResponse;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.clinic.repository.ClinicSlugHistoryRepository;
import press.mizhifei.dentist.clinic.repository.PublicDirectorySyncStateRepository;
import press.mizhifei.dentist.db.SchemaContract;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public directory against a real PostgreSQL database migrated with the
 * db-migrations Flyway scripts: the repository queries, slug uniqueness
 * across current and retired slugs, rename redirects, and replica
 * coordination through {@code FOR UPDATE SKIP LOCKED}.
 *
 * <p>Runs only when {@code TEST_DATABASE_URL} is set (CI postgres service or
 * local Docker). Rows are tagged with a per-run token and removed afterwards,
 * so the test shares the contract database safely.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringJUnitConfig(PublicDirectoryDatabaseTest.Config.class)
class PublicDirectoryDatabaseTest {

    private static final String RUN = Long.toString(System.nanoTime(), 36);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ClinicRepository clinicRepository;
    @Autowired
    private ClinicSlugHistoryRepository slugHistoryRepository;
    @Autowired
    private ClinicSlugService slugService;
    @Autowired
    private PublicClinicDirectoryService directoryService;
    @Autowired
    private PublicDirectorySyncService syncService;
    @Autowired
    private CountingTrigger trigger;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        SchemaContract.migrateAndValidate(url(), user(), password(), "press.mizhifei.dentist.clinic.model");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from services where clinic_id in (select id from clinics where name like ?)",
                "%" + RUN + "%");
        jdbc.update("delete from clinics where name like ?", "%" + RUN + "%");
        jdbc.update("update public_directory_sync set published_fingerprint = null, last_hook_at = null, "
                + "last_attempt_at = null, last_error = null where id = 1");
    }

    @Test
    void approvedClinicsGetSlugsAndOnlyOptedInOnesArePublished() {
        long listed = insertClinic("Harbour Dental " + RUN, "manly", "New South Wales", true, true);
        long unlisted = insertClinic("Quiet Dental " + RUN, "Manly", "NSW", true, false);
        long pending = insertClinic("Pending Dental " + RUN, "Manly", "NSW", false, true);

        assertThat(syncService.assignMissingSlugs()).isGreaterThanOrEqualTo(2);

        String listedSlug = slugOf(listed);
        assertThat(listedSlug).isEqualTo("harbour-dental-" + RUN.toLowerCase(Locale.ROOT) + "-manly");
        assertThat(slugOf(unlisted)).isNotNull();
        assertThat(slugOf(pending)).isNull();

        List<PublicClinicResponse> mine = myClinics();
        assertThat(mine).extracting(PublicClinicResponse::slug).containsExactly(listedSlug);
        assertThat(mine.get(0).address().suburb()).isEqualTo("Manly");
        assertThat(mine.get(0).address().state()).isEqualTo("NSW");
        assertThat(mine.get(0).address().country()).isEqualTo("AU");
    }

    @Test
    void namesThatShareABaseGetNumberedSlugs() {
        long first = insertClinic("Bay Dental " + RUN, "Manly", "NSW", true, true);
        long second = insertClinic("Bay Dental " + RUN + " Manly", "Manly", "NSW", true, true);

        syncService.assignMissingSlugs();

        String base = "bay-dental-" + RUN.toLowerCase(Locale.ROOT) + "-manly";
        assertThat(List.of(slugOf(first), slugOf(second))).containsExactlyInAnyOrder(base, base + "-2");
    }

    @Test
    void renameRetiresTheOldSlugWhichRedirectsAndCanBeReclaimed() {
        long id = insertClinic("Old Name " + RUN, "Ryde", "NSW", true, true);
        syncService.assignMissingSlugs();
        String oldSlug = slugOf(id);

        String newSlug = rename(id, "New Name " + RUN);

        assertThat(newSlug).isNotEqualTo(oldSlug);
        assertThat(directoryService.findBySlug(newSlug)).isInstanceOf(PublicClinicDirectoryService.Found.class);
        assertThat(directoryService.findBySlug(oldSlug))
                .isEqualTo(new PublicClinicDirectoryService.MovedPermanently(newSlug));
        assertThat(myClinics().get(0).previousSlugs()).containsExactly(oldSlug);

        // Renaming back reclaims the old slug; the newer one becomes the retired one.
        assertThat(rename(id, "Old Name " + RUN)).isEqualTo(oldSlug);
        assertThat(slugHistoryRepository.findById(oldSlug)).isEmpty();
        assertThat(directoryService.findBySlug(newSlug))
                .isEqualTo(new PublicClinicDirectoryService.MovedPermanently(oldSlug));

        // Delisting hides the clinic and stops the redirect.
        jdbc.update("update clinics set listed_publicly = false where id = ?", id);
        assertThat(directoryService.findBySlug(oldSlug)).isInstanceOf(PublicClinicDirectoryService.NotFound.class);
        assertThat(directoryService.findBySlug(newSlug)).isInstanceOf(PublicClinicDirectoryService.NotFound.class);
    }

    @Test
    void retiredSlugIsNeverGivenToAnotherClinic() {
        long id = insertClinic("Corner Dental " + RUN, "Ryde", "NSW", true, true);
        syncService.assignMissingSlugs();
        String retired = slugOf(id);
        rename(id, "Renamed Corner Dental " + RUN);

        long newcomer = insertClinic("Corner Dental " + RUN + " Ryde", "Ryde", "NSW", true, true);
        syncService.assignMissingSlugs();

        assertThat(slugOf(newcomer)).isEqualTo(retired + "-2");
    }

    @Test
    void hoursAndActiveServicesArePublished() {
        long id = insertClinic("Hours Dental " + RUN, "Ryde", "VIC", true, true);
        syncService.assignMissingSlugs();
        jdbc.update("insert into clinic_operating_hours (clinic_id, day_of_week, open_time, close_time, is_open)"
                + " values (?, 1, '08:30', '17:30', true), (?, 0, '09:00', '12:00', false)", id, id);
        jdbc.update("insert into services (clinic_id, name, is_active) values (?, 'Fillings', true),"
                + " (?, 'Check-up and clean', true), (?, 'Retired service', false)", id, id, id);

        PublicClinicResponse clinic = myClinics().get(0);

        assertThat(clinic.operatingHours()).extracting(hours -> hours.dayOfWeek() + " " + hours.openTime()
                + "-" + hours.closeTime() + " " + hours.isOpen())
                .containsExactly("0 09:00-12:00 false", "1 08:30-17:30 true");
        assertThat(clinic.services()).containsExactly("Check-up and clean", "Fillings");
        assertThat(clinic.acceptsOnlineBooking()).isTrue();
    }

    @Test
    void aChangeTriggersOneRebuildThenWaitsForTheHour() {
        insertClinic("Trigger Dental " + RUN, "Ryde", "NSW", true, true);
        int before = trigger.calls;

        assertThat(syncService.synchronize()).isEqualTo(Outcome.TRIGGERED);
        assertThat(syncService.synchronize()).isEqualTo(Outcome.UP_TO_DATE);

        insertClinic("Second Trigger Dental " + RUN, "Ryde", "NSW", true, true);
        assertThat(syncService.synchronize()).isEqualTo(Outcome.THROTTLED);
        assertThat(trigger.calls - before).isEqualTo(1);
    }

    @Test
    void aReplicaSkipsThePassWhileAnotherHoldsTheRow() throws Exception {
        long id = insertClinic("Locked Dental " + RUN, "Ryde", "NSW", true, true);
        try (Connection other = DriverManager.getConnection(url(), user(), password());
                Statement statement = other.createStatement()) {
            other.setAutoCommit(false);
            statement.execute("select id from public_directory_sync where id = 1 for update");

            assertThat(syncService.assignMissingSlugs()).isZero();
            assertThat(syncService.triggerRebuildIfChanged()).isEqualTo(Outcome.BUSY);
            assertThat(slugOf(id)).isNull();
            other.rollback();
        }
        assertThat(syncService.assignMissingSlugs()).isGreaterThanOrEqualTo(1);
        assertThat(slugOf(id)).isNotNull();
    }

    private long insertClinic(String name, String suburb, String state, boolean approved, boolean listed) {
        Long id = jdbc.queryForObject("insert into clinics (name, address, city, state, zip_code, country, "
                        + "enabled, approved, listed_publicly, created_at, updated_at) values "
                        + "(?, '1 Main Street', ?, ?, '2000', 'Australia', ?, ?, ?, now(), now()) returning id",
                Long.class, name, suburb, state, approved, approved, listed);
        return id;
    }

    private String slugOf(long id) {
        return jdbc.queryForObject("select slug from clinics where id = ?", String.class, id);
    }

    private String rename(long id, String newName) {
        return transactionTemplate.execute(status -> {
            Clinic clinic = clinicRepository.findById(id).orElseThrow();
            clinic.setName(newName);
            slugService.refreshSlug(clinic, java.time.LocalDateTime.now());
            return clinicRepository.saveAndFlush(clinic).getSlug();
        });
    }

    private List<PublicClinicResponse> myClinics() {
        String token = RUN.toLowerCase(Locale.ROOT);
        return directoryService.listClinics().response().clinics().stream()
                .filter(clinic -> clinic.slug().contains(token))
                .toList();
    }

    private static String url() {
        return System.getenv("TEST_DATABASE_URL");
    }

    private static String user() {
        return System.getenv("TEST_DATABASE_USERNAME");
    }

    private static String password() {
        return System.getenv("TEST_DATABASE_PASSWORD");
    }

    static final class CountingTrigger implements FrontendRebuildTrigger {

        int calls;

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void trigger() {
            calls++;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ClinicRepository.class)
    static class Config {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url(), user(), password());
            dataSource.setDriverClassName("org.postgresql.Driver");
            return dataSource;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("press.mizhifei.dentist.clinic.model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            // The physical naming Spring Boot applies to every service.
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName(),
                    "hibernate.hbm2ddl.auto", "none"));
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        CountingTrigger countingTrigger() {
            return new CountingTrigger();
        }

        @Bean
        PublicDirectoryProperties publicDirectoryProperties() {
            return new PublicDirectoryProperties("/api/public/clinics", Duration.ofMinutes(5),
                    new PublicDirectoryProperties.Sync(false, Duration.ofMinutes(5), Duration.ofMinutes(1)),
                    new PublicDirectoryProperties.DeployHook("", Duration.ofHours(1), Duration.ofMinutes(10),
                            Duration.ofSeconds(5), Duration.ofSeconds(10)));
        }

        @Bean
        ClinicSlugService clinicSlugService(ClinicRepository clinicRepository,
                ClinicSlugHistoryRepository slugHistoryRepository) {
            return new ClinicSlugService(clinicRepository, slugHistoryRepository);
        }

        @Bean
        PublicClinicDirectoryService publicClinicDirectoryService(
                ClinicRepository clinicRepository,
                press.mizhifei.dentist.clinic.repository.ClinicOperatingHoursRepository hoursRepository,
                press.mizhifei.dentist.clinic.repository.ServiceRepository serviceRepository,
                ClinicSlugHistoryRepository slugHistoryRepository,
                Clock clock) {
            return new PublicClinicDirectoryService(
                    clinicRepository, hoursRepository, serviceRepository, slugHistoryRepository, clock);
        }

        @Bean
        PublicDirectorySyncService publicDirectorySyncService(
                ClinicRepository clinicRepository,
                PublicDirectorySyncStateRepository syncStateRepository,
                ClinicSlugService slugService,
                PublicClinicDirectoryService directoryService,
                CountingTrigger trigger,
                PlatformTransactionManager transactionManager,
                Clock clock,
                PublicDirectoryProperties properties) {
            return new PublicDirectorySyncService(clinicRepository, syncStateRepository, slugService,
                    directoryService, trigger, transactionManager, clock, properties);
        }
    }
}
