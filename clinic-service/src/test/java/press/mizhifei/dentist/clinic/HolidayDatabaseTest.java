package press.mizhifei.dentist.clinic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import press.mizhifei.dentist.clinic.controller.HolidayController;
import press.mizhifei.dentist.clinic.dto.HolidayRequest;
import press.mizhifei.dentist.clinic.model.HolidayType;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real JPA writes prove occurrence conflicts, rollbacks and cross-request locking. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "public-directory.sync.enabled=false",
        "public-directory.deploy-hook.url="
})
@WithMockUser(roles = "SYSTEM_ADMIN")
class HolidayDatabaseTest {

    private static final Jwt ADMIN = Jwt.withTokenValue("test-only").header("alg", "RS256")
            .subject("55").claim("roles", List.of("SYSTEM_ADMIN")).build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Autowired
    private HolidayController controller;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;

    private final String name = "Holiday contract " + UUID.randomUUID();
    private long clinicId;

    @BeforeEach
    void createClinic() {
        clinicId = jdbc.queryForObject("insert into clinics (name) values (?) returning id", Long.class, name);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from clinics where name like ?", name + "%");
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-10, false, 2026-11-10, false, 409",
            "2025-11-10, true,  2026-11-10, false, 409",
            "2026-11-10, false, 2025-11-10, true,  409",
            "2025-11-10, true,  2026-11-10, true,  409",
            "2024-02-29, true,  2027-02-28, false, 409",
            "2027-02-28, false, 2024-02-29, true,  409",
            "2024-02-29, true,  2026-02-28, true,  409",
            "2026-02-28, true,  2024-02-29, true,  409",
            "2024-02-29, true,  2028-02-28, false, 200",
            "2027-11-10, true,  2026-11-10, false, 200"
    })
    void writesRejectOverlappingOccurrences(String firstDate, boolean firstRecurring,
            String secondDate, boolean secondRecurring, int expectedStatus) {
        assertEquals(200, controller.create(clinicId, request(firstDate, firstRecurring), ADMIN)
                .getStatusCode().value());
        assertEquals(expectedStatus, controller.create(clinicId, request(secondDate, secondRecurring), ADMIN)
                .getStatusCode().value());
        assertEquals(expectedStatus == 409 ? 1 : 2,
                jdbc.queryForObject("select count(*) from clinic_holidays where clinic_id = ?", Integer.class, clinicId));
    }

    @Test
    void conflictingUpdateLeavesTheStoredDateUnchanged() {
        controller.create(clinicId, request("2025-11-10", true), ADMIN);
        long id = controller.create(clinicId, request("2026-12-01", false), ADMIN)
                .getBody().getDataObject().id();

        assertEquals(409, controller.update(clinicId, id, request("2026-11-10", false), ADMIN)
                .getStatusCode().value());
        assertEquals(LocalDate.of(2026, 12, 1), jdbc.queryForObject(
                "select holiday_date from clinic_holidays where id = ?", LocalDate.class, id));
    }

    @Test
    void identicalOccurrencesInDifferentClinicsAreAllowed() {
        long otherClinic = jdbc.queryForObject("insert into clinics (name) values (?) returning id",
                Long.class, name + " other");
        controller.create(clinicId, request("2025-11-10", true), ADMIN);
        assertEquals(200, controller.create(otherClinic, request("2026-11-10", true), ADMIN)
                .getStatusCode().value());
    }

    @Test
    void concurrentWritesCannotCreateOverlappingOccurrences() throws Exception {
        CountDownLatch firstSaved = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> asAdmin(() -> transactions.execute(status -> {
                var response = controller.create(clinicId, request("2025-11-10", true), ADMIN);
                firstSaved.countDown();
                try {
                    assertTrue(commitFirst.await(15, TimeUnit.SECONDS), "release the first transaction");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return response;
            })));
            try {
                assertTrue(firstSaved.await(10, TimeUnit.SECONDS), "the first holiday must be saved");
                var second = executor.submit(() -> asAdmin(() ->
                        controller.create(clinicId, request("2026-11-10", false), ADMIN)));
                // Wait for an observed DB lock (or a premature completion), not a guessed sleep.
                await().atMost(Duration.ofSeconds(10)).until(() -> second.isDone()
                        || jdbc.queryForObject("select count(*) from pg_stat_activity "
                                + "where datname = current_database() and wait_event_type = 'Lock' "
                                + "and query like '%clinics%'", Integer.class) > 0);
                commitFirst.countDown();
                assertEquals(200, first.get(10, TimeUnit.SECONDS).getStatusCode().value());
                assertEquals(409, second.get(10, TimeUnit.SECONDS).getStatusCode().value());
            } finally {
                commitFirst.countDown();
            }
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from clinic_holidays where clinic_id = ?",
                Integer.class, clinicId));
    }

    private static HolidayRequest request(String date, boolean recurring) {
        return new HolidayRequest("Closure", LocalDate.parse(date), null, HolidayType.CLINIC_CLOSURE,
                true, null, null, recurring, null, null);
    }

    private static <T> T asAdmin(Callable<T> action) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                ADMIN, List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
