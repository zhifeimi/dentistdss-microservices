package press.mizhifei.dentist.auth.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import press.mizhifei.dentist.auth.client.AuditClient;
import press.mizhifei.dentist.auth.dto.AuditEntryRequest;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the best-effort audit emitter. The publisher's contract is
 * that emission never breaks an authentication flow: it must dispatch the
 * fully assembled payload, and swallow every failure from the client or the
 * executor. A direct executor ({@code Runnable::run}) runs dispatch inline
 * so the assertions see the client call synchronously.
 */
class AuditEventPublisherTest {

    private AuditClient auditClient;
    private AuditEventPublisher publisher;

    @BeforeEach
    void setUp() {
        auditClient = mock(AuditClient.class);
        publisher = new AuditEventPublisher(auditClient, Runnable::run);
    }

    @Test
    void publishDispatchesTheAssembledRequestToTheAuditClient() {
        publisher.publish(
                "LOGIN_SUCCESS",
                "user:42",
                42L,
                9L,
                Map.of("familyId", "family-1"));

        ArgumentCaptor<AuditEntryRequest> captor =
                ArgumentCaptor.forClass(AuditEntryRequest.class);
        verify(auditClient).record(captor.capture());
        AuditEntryRequest request = captor.getValue();
        assertEquals("LOGIN_SUCCESS", request.getAction());
        assertEquals("user:42", request.getTarget());
        assertEquals(42L, request.getAssertedUserId());
        assertEquals(9L, request.getAssertedClinicId());
        assertEquals(Map.of("familyId", "family-1"), request.getContext());
    }

    @Test
    void auditClientFailureNeverPropagatesToTheCaller() {
        when(auditClient.record(any(AuditEntryRequest.class)))
                .thenThrow(new RuntimeException("audit-service unavailable"));

        assertDoesNotThrow(() -> publisher.publish(
                "LOGOUT",
                "user:42",
                42L,
                null,
                Map.of("familyId", "family-1")));
    }

    @Test
    void executorRejectionNeverPropagatesAndNeverCallsTheClient() {
        AuditEventPublisher saturated = new AuditEventPublisher(
                auditClient,
                command -> {
                    throw new RejectedExecutionException("audit queue saturated");
                });

        assertDoesNotThrow(() -> saturated.publish(
                "USER_REGISTERED",
                "user:77",
                77L,
                null,
                Map.of("role", "PATIENT")));

        verifyNoInteractions(auditClient);
    }
}
