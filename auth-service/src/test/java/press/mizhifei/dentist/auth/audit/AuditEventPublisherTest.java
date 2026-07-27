package press.mizhifei.dentist.auth.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the publisher, which now delegates to the durable
 * transactional outbox (AUDIT-01). The contract changed with the outbox:
 * emission no longer touches the network and no longer swallows failures —
 * the write joins the caller's transaction, so a database failure
 * propagates to the caller (intentional coupling; see the
 * {@link AuditOutboxService} javadoc).
 */
class AuditEventPublisherTest {

    private AuditOutboxService auditOutboxService;
    private AuditEventPublisher publisher;

    @BeforeEach
    void setUp() {
        auditOutboxService = mock(AuditOutboxService.class);
        publisher = new AuditEventPublisher(auditOutboxService);
    }

    @Test
    void publishForwardsEveryArgumentToTheOutboxWriter() {
        publisher.publish(
                "LOGIN_SUCCESS",
                "user:42",
                42L,
                9L,
                Map.of("familyId", "family-1"));

        verify(auditOutboxService).publish(
                eq("LOGIN_SUCCESS"),
                eq("user:42"),
                eq(42L),
                eq(9L),
                eq(Map.of("familyId", "family-1")));
    }

    @Test
    void nullClinicAndContextForwardAsNull() {
        publisher.publish("LOGOUT", "user:42", 42L, null, null);

        verify(auditOutboxService).publish(
                eq("LOGOUT"), eq("user:42"), eq(42L), isNull(), isNull());
    }

    @Test
    void outboxWriterFailurePropagatesToTheCaller() {
        doThrow(new RuntimeException("database unavailable"))
                .when(auditOutboxService).publish(any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class, () -> publisher.publish(
                "LOGIN_SUCCESS",
                "user:42",
                42L,
                9L,
                Map.of("familyId", "family-1")));
    }
}
