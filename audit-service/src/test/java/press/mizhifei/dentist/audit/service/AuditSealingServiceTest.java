package press.mizhifei.dentist.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import press.mizhifei.dentist.audit.model.AuditEntry;
import press.mizhifei.dentist.audit.model.AuditSeal;
import press.mizhifei.dentist.audit.repository.AuditEntryRepository;
import press.mizhifei.dentist.audit.repository.AuditSealRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the audit batch sealer (AUDIT-01): the first seal chains
 * from {@code GENESIS} over the leading {@code _id} range, subsequent seals
 * extend the previous head with a recomputable chained hash, full batches
 * keep catching up within a tick, a duplicate sequence (concurrent sealer)
 * stops the round without failing the cycle, and a disabled sealer never
 * touches the repositories.
 */
class AuditSealingServiceTest {

    private static final String ID_1 = "665f00000000000000000010";
    private static final String ID_2 = "665f00000000000000000020";
    private static final String ID_3 = "665f00000000000000000030";
    private static final String ID_4 = "665f00000000000000000040";

    private AuditEntryRepository entryRepository;
    private AuditSealRepository sealRepository;
    private AuditSealingService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(AuditEntryRepository.class);
        sealRepository = mock(AuditSealRepository.class);
        service = new AuditSealingService(entryRepository, sealRepository);
        ReflectionTestUtils.setField(service, "sealingEnabled", true);
        ReflectionTestUtils.setField(service, "batchSize", 2);
        ReflectionTestUtils.setField(service, "maxRoundsPerTick", 10);
    }

    @Test
    void emptyBacklogCreatesNoSeal() {
        when(sealRepository.findTopByOrderBySequenceDesc()).thenReturn(Optional.empty());
        when(entryRepository.findByIdGreaterThanAndContentHashNotNullOrderByIdAsc(
                eq(AuditSealingService.FIRST_OBJECT_ID), any(Pageable.class)))
                .thenReturn(List.of());

        service.sealPendingEntries();

        verify(sealRepository, never()).save(any());
    }

    @Test
    void genesisSealChainsFromGenesisAndCoversTheBatch() {
        when(sealRepository.findTopByOrderBySequenceDesc()).thenReturn(Optional.empty());
        List<AuditEntry> batch = List.of(entry(ID_1, "hash-1"), entry(ID_2, "hash-2"));
        when(entryRepository.findByIdGreaterThanAndContentHashNotNullOrderByIdAsc(
                eq(AuditSealingService.FIRST_OBJECT_ID), any(Pageable.class)))
                .thenReturn(batch)
                .thenReturn(List.of());

        service.sealPendingEntries();

        ArgumentCaptor<AuditSeal> captor = ArgumentCaptor.forClass(AuditSeal.class);
        verify(sealRepository).save(captor.capture());
        AuditSeal seal = captor.getValue();
        assertEquals(1L, seal.getSequence());
        assertEquals("GENESIS", seal.getPreviousSealHash());
        assertEquals(ID_1, seal.getFirstId());
        assertEquals(ID_2, seal.getLastId());
        assertEquals(2L, seal.getCount());
        assertNotNull(seal.getSealedAt());

        String expectedRoot = AuditHashes.sha256Hex("hash-1\nhash-2");
        assertEquals(expectedRoot, seal.getBatchRoot());
        assertEquals(
                AuditHashes.sha256Hex("GENESIS|1|" + ID_1 + "|" + ID_2 + "|2|" + expectedRoot),
                seal.getSealHash());
    }

    @Test
    void chainedSealExtendsThePreviousHead() {
        AuditSeal last = AuditSeal.builder()
                .sequence(5L)
                .lastId(ID_2)
                .sealHash("previous-seal-hash")
                .build();
        when(sealRepository.findTopByOrderBySequenceDesc()).thenReturn(Optional.of(last));
        List<AuditEntry> batch = List.of(entry(ID_3, "hash-3"));
        when(entryRepository.findByIdGreaterThanAndContentHashNotNullOrderByIdAsc(
                eq(ID_2), any(Pageable.class)))
                .thenReturn(batch);

        service.sealPendingEntries();

        ArgumentCaptor<AuditSeal> captor = ArgumentCaptor.forClass(AuditSeal.class);
        verify(sealRepository).save(captor.capture());
        AuditSeal seal = captor.getValue();
        assertEquals(6L, seal.getSequence());
        assertEquals("previous-seal-hash", seal.getPreviousSealHash());
        assertEquals(ID_3, seal.getFirstId());
        assertEquals(ID_3, seal.getLastId());
        assertEquals(1L, seal.getCount());

        String expectedRoot = AuditHashes.sha256Hex("hash-3");
        assertEquals(expectedRoot, seal.getBatchRoot());
        assertEquals(
                AuditHashes.sha256Hex(
                        "previous-seal-hash|6|" + ID_3 + "|" + ID_3 + "|1|" + expectedRoot),
                seal.getSealHash());
    }

    @Test
    void fullBatchKeepsCatchingUpWithinTheTick() {
        when(sealRepository.findTopByOrderBySequenceDesc()).thenReturn(Optional.empty());
        List<AuditEntry> batchA = List.of(entry(ID_1, "hash-1"), entry(ID_2, "hash-2"));
        List<AuditEntry> batchB = List.of(entry(ID_3, "hash-3"), entry(ID_4, "hash-4"));
        when(entryRepository.findByIdGreaterThanAndContentHashNotNullOrderByIdAsc(
                eq(AuditSealingService.FIRST_OBJECT_ID), any(Pageable.class)))
                .thenReturn(batchA)
                .thenReturn(batchB)
                .thenReturn(List.of());

        service.sealPendingEntries();

        verify(sealRepository, times(2)).save(any(AuditSeal.class));
    }

    @Test
    void duplicateSequenceStopsTheRoundWithoutFailingTheCycle() {
        when(sealRepository.findTopByOrderBySequenceDesc()).thenReturn(Optional.empty());
        when(entryRepository.findByIdGreaterThanAndContentHashNotNullOrderByIdAsc(
                eq(AuditSealingService.FIRST_OBJECT_ID), any(Pageable.class)))
                .thenReturn(List.of(entry(ID_1, "hash-1"), entry(ID_2, "hash-2")));
        when(sealRepository.save(any(AuditSeal.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key: sequence"));

        assertDoesNotThrow(() -> service.sealPendingEntries());

        verify(sealRepository, times(1)).save(any(AuditSeal.class));
    }

    @Test
    void disabledSealingIsANoOp() {
        ReflectionTestUtils.setField(service, "sealingEnabled", false);

        service.sealPendingEntries();

        verifyNoInteractions(entryRepository, sealRepository);
    }

    private static AuditEntry entry(String id, String contentHash) {
        return AuditEntry.builder()
                .id(id)
                .action("LOGIN_SUCCESS")
                .contentHash(contentHash)
                .build();
    }
}
