package press.mizhifei.dentist.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import press.mizhifei.dentist.audit.dto.AuditEntryRequest;
import press.mizhifei.dentist.audit.dto.AuditEntryResponse;
import press.mizhifei.dentist.audit.model.AuditEntry;
import press.mizhifei.dentist.audit.repository.AuditEntryRepository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for audit ingestion (AUDIT-01): the saved document carries a
 * recomputable SHA-256 content hash assigned before the save, the actor is
 * always the verified service credential's subject (the request has no
 * actor field at all), and the response shape is unchanged — it exposes no
 * content hash.
 */
class AuditServiceRecordTest {

    private AuditEntryRepository repository;
    private AuditContentHasher contentHasher;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEntryRepository.class);
        contentHasher = new AuditContentHasher();
        service = new AuditService(repository, contentHasher);
        when(repository.save(any(AuditEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void recordAssignsARecomputableContentHashBeforeSave() {
        AuditEntryRequest request = AuditEntryRequest.builder()
                .action("LOGIN_SUCCESS")
                .target("user:42")
                .assertedUserId(42L)
                .assertedClinicId(9L)
                .context(Map.of("familyId", "family-1"))
                .build();

        service.record(request, "auth-service");

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).save(captor.capture());
        AuditEntry saved = captor.getValue();
        assertNotNull(saved.getContentHash());
        assertTrue(saved.getContentHash().matches("[0-9a-f]{64}"));
        assertEquals(contentHasher.hash(saved), saved.getContentHash());
        assertEquals("LOGIN_SUCCESS", saved.getAction());
        assertEquals(Map.of("familyId", "family-1"), saved.getContext());
        assertNotNull(saved.getTimestamp());
    }

    @Test
    void actorComesFromTheVerifiedSubjectNeverTheRequest() {
        service.record(AuditEntryRequest.builder().action("READ").build(), "notification-service");

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).save(captor.capture());
        assertEquals("notification-service", captor.getValue().getActor());
    }

    @Test
    void responseShapeMapsTheDocumentAndCarriesNoContentHash() {
        AuditEntryRequest request = AuditEntryRequest.builder()
                .action("LOGIN_SUCCESS")
                .target("user:42")
                .assertedUserId(42L)
                .assertedClinicId(9L)
                .context(Map.of("familyId", "family-1"))
                .build();

        AuditEntryResponse response = service.record(request, "auth-service");

        // AuditEntryResponse intentionally exposes no contentHash accessor —
        // the ingest contract is unchanged; these are all the fields.
        assertEquals("auth-service", response.getActor());
        assertEquals("LOGIN_SUCCESS", response.getAction());
        assertEquals("user:42", response.getTarget());
        assertEquals(42L, response.getAssertedUserId());
        assertEquals(9L, response.getAssertedClinicId());
        assertEquals(Map.of("familyId", "family-1"), response.getContext());
        assertNotNull(response.getTimestamp());
    }
}
