package press.mizhifei.dentist.clinic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import press.mizhifei.dentist.clinic.controller.PublicClinicController;
import press.mizhifei.dentist.clinic.directory.PublicDirectoryProperties;
import press.mizhifei.dentist.clinic.directory.PublicDirectorySyncScheduler;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole clinic-service application against the contract database,
 * so bean wiring, {@code public-directory.*} binding and Hibernate's
 * validation of the migrated schema are checked the way production starts.
 * The focused tests build these beans by hand and cannot see wiring errors.
 *
 * <p>Skipped unless {@code TEST_DATABASE_URL} is set (CI postgres service or
 * local Docker).
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        // Keep the scheduled reconciler from touching the shared database.
        "public-directory.sync.enabled=false",
        "public-directory.deploy-hook.url=",
        "logging.level.org.springframework=INFO"
})
class ClinicServiceApplicationContextTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void publicDirectoryIsWiredWithItsDefaults() {
        assertThat(context.getBean(PublicClinicController.class)).isNotNull();
        assertThat(context.getBeansOfType(PublicDirectorySyncScheduler.class)).isEmpty();

        PublicDirectoryProperties properties = context.getBean(PublicDirectoryProperties.class);
        assertThat(properties.apiBasePath()).isEqualTo("/api/public/clinics");
        assertThat(properties.cacheMaxAge()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.deployHook().minInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.sync().interval()).isEqualTo(Duration.ofMinutes(5));
    }
}
