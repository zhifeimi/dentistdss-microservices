package press.mizhifei.dentist.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.auth.model.AuthAuditOutbox;
import press.mizhifei.dentist.auth.model.Role;
import press.mizhifei.dentist.auth.model.UserApprovalRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Repository and column-mapping checks against the real Flyway schema, which
 * the mocked unit tests cannot see: the approval queries bind status against
 * a varchar column (V1 has no approval_status type), and the audit outbox
 * context round-trips through jsonb with the Jackson 3 format mapper from
 * application.yml, and the security outbox writes work without a caller
 * transaction.
 *
 * <p>
 * Skipped unless {@code TEST_DATABASE_URL} is set (local Docker or the CI
 * postgres service container). Each test rolls back unless it opts out.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}",
        "spring.datasource.username=${TEST_DATABASE_USERNAME}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD}",
        "spring.cloud.config.enabled=false" })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JpaMappingDbTest {

    /** JPA only: the application class also enables Feign, discovery and async. */
    @Configuration
    @EntityScan(basePackageClasses = AuthAuditOutbox.class)
    @EnableJpaRepositories(basePackageClasses = UserApprovalRequestRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private UserApprovalRequestRepository approvals;

    @Autowired
    private AuthAuditOutboxRepository outbox;

    @Autowired
    private AuthSecurityOutboxRepository securityOutbox;

    @Autowired
    private EntityManager entityManager;

    @Test
    void approvalQueriesFilterByStatus() {
        UserApprovalRequest pending = approvals.saveAndFlush(UserApprovalRequest.builder()
                .userId(4242L).requestedRole(Role.CLINIC_ADMIN).clinicId(77L).build());

        assertThat(approvals.findByStatus("PENDING"))
                .extracting(UserApprovalRequest::getId).contains(pending.getId());
        assertThat(approvals.findByStatus("APPROVED"))
                .extracting(UserApprovalRequest::getId).doesNotContain(pending.getId());
        assertThat(approvals.findByUserIdAndStatus(4242L, "PENDING"))
                .map(UserApprovalRequest::getId).contains(pending.getId());
        assertThat(approvals.findByClinicIdAndStatusOrderByCreatedAtDesc(77L, "PENDING"))
                .extracting(UserApprovalRequest::getId).containsExactly(pending.getId());
    }

    @Test
    void auditContextRoundTripsAsJsonb() {
        ObjectNode context = JsonMapper.shared().createObjectNode()
                .put("ip", "203.0.113.7")
                .put("attempt", 2);
        AuthAuditOutbox row = outbox.saveAndFlush(AuthAuditOutbox.builder()
                .action("LOGIN_FAILED").context(context).createdAt(LocalDateTime.now()).build());
        entityManager.clear();

        Object ip = entityManager
                .createNativeQuery("select context->>'ip' from auth_audit_outbox where id = :id")
                .setParameter("id", row.getId())
                .getSingleResult();
        assertThat(ip).isEqualTo("203.0.113.7");
        assertThat(outbox.findById(row.getId()))
                .map(AuthAuditOutbox::getContext).contains(context);
    }

    /** The scheduled replay and reconcile jobs call these with no transaction open. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void securityOutboxWritesRunWithoutACallerTransaction() {
        LocalDateTime now = LocalDateTime.now();
        securityOutbox.upsertPending(4243L, "db-test-family", 60_000L, now.minusHours(2), now.minusHours(1));

        assertThat(securityOutbox.deleteExpired(now)).isGreaterThanOrEqualTo(1);
        assertThat(securityOutbox.deleteByUserIdAndFamilyId(4243L, "db-test-family")).isZero();
    }
}
