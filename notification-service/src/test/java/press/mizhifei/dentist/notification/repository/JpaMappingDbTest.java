package press.mizhifei.dentist.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import press.mizhifei.dentist.notification.model.Notification;
import press.mizhifei.dentist.notification.model.NotificationStatus;
import press.mizhifei.dentist.notification.model.NotificationTemplate;
import press.mizhifei.dentist.notification.model.NotificationType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Repository and column-mapping checks against the real Flyway schema, which
 * the mocked unit tests cannot see: notification type/status are PostgreSQL
 * named enums, and metadata round-trips through jsonb with the Jackson 3
 * format mapper from application.yml.
 *
 * <p>
 * Skipped unless {@code TEST_DATABASE_URL} is set (local Docker or the CI
 * postgres service container). Each test rolls back.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}",
        "spring.datasource.username=${TEST_DATABASE_USERNAME}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD}",
        "spring.cloud.config.enabled=false" })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JpaMappingDbTest {

    /** JPA only: the application class also enables Feign, discovery, async and scheduling. */
    @Configuration
    @EntityScan(basePackageClasses = Notification.class)
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private NotificationTemplateRepository templates;

    @Autowired
    private EntityManager entityManager;

    @Test
    void notificationEnumsAndMetadataRoundTrip() {
        ObjectNode metadata = JsonMapper.shared().createObjectNode().put("appointmentId", 10001);
        Notification saved = notifications.saveAndFlush(Notification.builder()
                .userId(55L).type(NotificationType.EMAIL).body("Your appointment is confirmed")
                .metadata(metadata).build());
        entityManager.clear();

        Object appointmentId = entityManager
                .createNativeQuery("select metadata->>'appointmentId' from notifications where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(appointmentId).isEqualTo("10001");
        assertThat(notifications.findByUserIdAndStatusOrderByCreatedAtDesc(55L, NotificationStatus.PENDING))
                .singleElement()
                .returns(saved.getId(), Notification::getId)
                .returns(NotificationType.EMAIL, Notification::getType)
                .returns(metadata, Notification::getMetadata);
    }

    @Test
    void templateLookupBindsTheTypeEnum() {
        NotificationTemplate template = templates.saveAndFlush(NotificationTemplate.builder()
                .name("db-test-template").type(NotificationType.IN_APP).bodyTemplate("Hello {{name}}").build());

        assertThat(templates.findByNameAndTypeAndIsActiveTrue("db-test-template", NotificationType.IN_APP))
                .map(NotificationTemplate::getId).contains(template.getId());
        assertThat(templates.findByNameAndTypeAndIsActiveTrue("db-test-template", NotificationType.EMAIL))
                .isEmpty();
    }
}
