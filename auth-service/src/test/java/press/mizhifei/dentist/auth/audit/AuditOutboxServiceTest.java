package press.mizhifei.dentist.auth.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import press.mizhifei.dentist.auth.client.AuditClient;
import press.mizhifei.dentist.auth.dto.AuditEntryRequest;
import press.mizhifei.dentist.auth.model.AuthAuditOutbox;
import press.mizhifei.dentist.auth.repository.AuthAuditOutboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the durable audit outbox writer and relay (AUDIT-01):
 * {@code publish} saves a fully populated row with {@code attempts = 0};
 * the relay delivers claimed rows and deletes only after a confirmed
 * delivery; failed deliveries record the attempt (with a truncated error)
 * and never discard the row; the claim applies the configured retry
 * backoff; claim failures are swallowed so the next cycle retries; and a
 * disabled relay is a complete no-op.
 */
class AuditOutboxServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuthAuditOutboxRepository outboxRepository;
    private AuditClient auditClient;
    private AuditOutboxService service;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(AuthAuditOutboxRepository.class);
        auditClient = mock(AuditClient.class);
        service = new AuditOutboxService(outboxRepository, auditClient, objectMapper);
        ReflectionTestUtils.setField(service, "relayEnabled", true);
        ReflectionTestUtils.setField(service, "retryBackoffMs", 30000L);
        ReflectionTestUtils.setField(service, "relayBatchSize", 100);
        ReflectionTestUtils.setField(service, "staleWarnThreshold", 20);
    }

    @Test
    void publishSavesAllFieldsWithZeroAttempts() {
        service.publish("LOGIN_SUCCESS", "user:42", 42L, 9L, Map.of("familyId", "family-1"));

        ArgumentCaptor<AuthAuditOutbox> captor = ArgumentCaptor.forClass(AuthAuditOutbox.class);
        verify(outboxRepository).save(captor.capture());
        AuthAuditOutbox row = captor.getValue();
        assertEquals("LOGIN_SUCCESS", row.getAction());
        assertEquals("user:42", row.getTarget());
        assertEquals(42L, row.getAssertedUserId());
        assertEquals(9L, row.getAssertedClinicId());
        assertEquals(0, row.getAttempts());
        assertNotNull(row.getCreatedAt());
        assertEquals(objectMapper.valueToTree(Map.of("familyId", "family-1")), row.getContext());
    }

    @Test
    void publishStoresNullContextAsNull() {
        service.publish("LOGOUT", "user:42", 42L, null, null);

        ArgumentCaptor<AuthAuditOutbox> captor = ArgumentCaptor.forClass(AuthAuditOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertNull(captor.getValue().getContext());
        assertNull(captor.getValue().getAssertedClinicId());
        assertEquals(0, captor.getValue().getAttempts());
    }

    @Test
    void relayDeliversClaimedRowsAndDeletesOnlyAfterConfirmation() {
        AuthAuditOutbox row1 = row(1L, "LOGIN_SUCCESS", "user:42", 42L, 9L,
                objectMapper.valueToTree(Map.of("familyId", "family-1")));
        AuthAuditOutbox row2 = row(2L, "LOGOUT", "user:42", 42L, null, null);
        when(outboxRepository.claimPending(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(row1, row2));

        service.relayPendingEvents();

        ArgumentCaptor<AuditEntryRequest> sent = ArgumentCaptor.forClass(AuditEntryRequest.class);
        verify(auditClient, times(2)).record(sent.capture());
        assertEquals("LOGIN_SUCCESS", sent.getAllValues().get(0).getAction());
        assertEquals("user:42", sent.getAllValues().get(0).getTarget());
        assertEquals(42L, sent.getAllValues().get(0).getAssertedUserId());
        assertEquals(9L, sent.getAllValues().get(0).getAssertedClinicId());
        assertEquals(Map.of("familyId", "family-1"), sent.getAllValues().get(0).getContext());
        assertNull(sent.getAllValues().get(1).getContext());
        verify(outboxRepository).delete(row1);
        verify(outboxRepository).delete(row2);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void relayRecordsFailedAttemptsAndNeverDiscardsTheRow() {
        AuthAuditOutbox row = row(1L, "LOGIN_SUCCESS", "user:42", 42L, 9L, null);
        when(outboxRepository.claimPending(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(row));
        when(auditClient.record(any(AuditEntryRequest.class)))
                .thenThrow(new RuntimeException("x".repeat(600)));

        service.relayPendingEvents();

        verify(outboxRepository, never()).delete(any());
        verify(outboxRepository).save(row);
        assertEquals(1, row.getAttempts());
        assertNotNull(row.getLastAttemptAt());
        assertEquals(500, row.getLastError().length());
    }

    @Test
    void relayDisabledIsANoOp() {
        ReflectionTestUtils.setField(service, "relayEnabled", false);

        service.relayPendingEvents();

        verifyNoInteractions(outboxRepository, auditClient);
    }

    @Test
    void claimFailureIsSwallowedAndRetriedNextCycle() {
        when(outboxRepository.claimPending(any(LocalDateTime.class), eq(100)))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        assertDoesNotThrow(() -> service.relayPendingEvents());

        verifyNoInteractions(auditClient);
    }

    @Test
    void relayAppliesTheConfiguredRetryBackoffToTheClaim() {
        LocalDateTime before = LocalDateTime.now();

        service.relayPendingEvents();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxRepository).claimPending(captor.capture(), eq(100));
        LocalDateTime expected = before.minus(Duration.ofMillis(30000));
        assertTrue(!captor.getValue().isBefore(expected.minus(Duration.ofSeconds(1)))
                        && !captor.getValue().isAfter(expected.plus(Duration.ofSeconds(1))),
                "retryBefore should be now - retryBackoffMs");
    }

    @Test
    void staleRowsWarnAtTheConfiguredThreshold() {
        ReflectionTestUtils.setField(service, "staleWarnThreshold", 2);
        AuthAuditOutbox row = row(7L, "LOGIN_SUCCESS", "user:42", 42L, 9L, null);
        row.setAttempts(1); // next failure -> 2, a multiple of the threshold
        when(outboxRepository.claimPending(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(row));
        when(auditClient.record(any(AuditEntryRequest.class)))
                .thenThrow(new RuntimeException("audit-service unavailable"));

        Logger logger = (Logger) LoggerFactory.getLogger(AuditOutboxService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.relayPendingEvents();
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(2, row.getAttempts());
        assertTrue(appender.list.stream().anyMatch(event ->
                        event.getLevel() == Level.WARN
                                && event.getFormattedMessage().contains("remains queued")),
                "expected the stale-row monitoring warning");
    }

    private AuthAuditOutbox row(Long id, String action, String target, Long userId,
            Long clinicId, JsonNode context) {
        return AuthAuditOutbox.builder()
                .id(id)
                .action(action)
                .target(target)
                .assertedUserId(userId)
                .assertedClinicId(clinicId)
                .context(context)
                .createdAt(LocalDateTime.now())
                .attempts(0)
                .build();
    }
}
