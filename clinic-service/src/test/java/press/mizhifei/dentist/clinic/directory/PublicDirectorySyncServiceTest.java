package press.mizhifei.dentist.clinic.directory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import press.mizhifei.dentist.clinic.directory.PublicDirectorySyncService.Outcome;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.model.PublicDirectorySyncState;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.clinic.repository.PublicDirectorySyncStateRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicDirectorySyncServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);

    private ClinicRepository clinicRepository;
    private PublicDirectorySyncStateRepository syncStateRepository;
    private ClinicSlugService slugService;
    private PublicClinicDirectoryService directoryService;
    private FakeTrigger trigger;
    private NoOpTransactionManager transactionManager;
    private PublicDirectorySyncState state;
    private PublicDirectorySyncService sync;

    @BeforeEach
    void setUp() {
        clinicRepository = mock(ClinicRepository.class);
        syncStateRepository = mock(PublicDirectorySyncStateRepository.class);
        slugService = mock(ClinicSlugService.class);
        directoryService = mock(PublicClinicDirectoryService.class);
        trigger = new FakeTrigger();
        transactionManager = new NoOpTransactionManager();
        state = new PublicDirectorySyncState();
        state.setId(PublicDirectorySyncState.SINGLETON_ID);
        when(syncStateRepository.lockSingleton()).thenReturn(Optional.of(state));
        when(directoryService.currentFingerprint()).thenReturn("new-fingerprint");
        when(clinicRepository.findApprovedWithoutSlug()).thenReturn(List.of());

        sync = syncAt(NOW);
    }

    @Test
    void firstChangeTriggersARebuildAndRecordsIt() {
        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);

        assertThat(trigger.calls).isEqualTo(1);
        assertThat(state.getPublishedFingerprint()).isEqualTo("new-fingerprint");
        assertThat(state.getLastHookAt()).isEqualTo(NOW);
        assertThat(state.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(state.getLastError()).isNull();
    }

    @Test
    void unchangedDirectoryDoesNotRebuild() {
        state.setPublishedFingerprint("new-fingerprint");

        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.UP_TO_DATE);
        assertThat(trigger.calls).isZero();
    }

    @Test
    void atMostOneRebuildPerHour() {
        state.setPublishedFingerprint("old-fingerprint");
        state.setLastHookAt(NOW.minusMinutes(59));
        state.setLastAttemptAt(NOW.minusMinutes(59));

        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.THROTTLED);
        assertThat(trigger.calls).isZero();
        assertThat(state.getPublishedFingerprint()).isEqualTo("old-fingerprint");

        state.setLastHookAt(NOW.minusMinutes(60));
        state.setLastAttemptAt(NOW.minusMinutes(60));
        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);
        assertThat(trigger.calls).isEqualTo(1);
    }

    @Test
    void failedCallWaitsTheFullMinIntervalWithoutLeakingTheUrl() {
        trigger.failure = new FrontendRebuildTrigger.RebuildTriggerException("Deploy hook answered HTTP 500");

        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.FAILED);
        assertThat(state.getPublishedFingerprint()).isNull();
        assertThat(state.getLastHookAt()).isNull();
        assertThat(state.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(state.getLastError()).isEqualTo("Deploy hook answered HTTP 500").doesNotContain("secret");

        trigger.failure = null;
        assertThat(syncAt(NOW.plusMinutes(10)).triggerRebuildIfChanged()).isEqualTo(Outcome.THROTTLED);
        assertThat(trigger.calls).isEqualTo(1);

        assertThat(syncAt(NOW.plusHours(1)).triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);
        assertThat(trigger.calls).isEqualTo(2);
        assertThat(state.getLastError()).isNull();
    }

    @Test
    void acceptedHookWithFailedFinalizationStillRebuildsWhenPayloadReturnsToOldFingerprint() {
        state.setPublishedFingerprint("old-fingerprint");
        state.setLastHookAt(NOW.minusHours(2));
        trigger.afterAccept = () -> transactionManager.failNextTransaction = true;

        assertThatThrownBy(sync::triggerRebuildIfChanged).isInstanceOf(IllegalStateException.class);
        assertThat(trigger.acceptedCalls).isEqualTo(1);

        // The claimed row survives the failed finalization and service restart.
        trigger.afterAccept = () -> {};
        when(directoryService.currentFingerprint()).thenReturn("old-fingerprint");
        sync = syncAt(NOW.plusHours(1));

        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);
        assertThat(trigger.acceptedCalls).isEqualTo(2);
        assertThat(state.getPublishedFingerprint()).isEqualTo("old-fingerprint");
    }

    @Test
    void acceptedHookWithFailedFinalizationKeepsHourlyCooldownAfterRestart() {
        state.setPublishedFingerprint("old-fingerprint");
        state.setLastHookAt(NOW.minusHours(2));
        trigger.afterAccept = () -> transactionManager.failNextTransaction = true;

        assertThatThrownBy(sync::triggerRebuildIfChanged).isInstanceOf(IllegalStateException.class);
        assertThat(trigger.acceptedCalls).isEqualTo(1);
        trigger.afterAccept = () -> {};

        assertThat(syncAt(NOW.plusMinutes(10)).triggerRebuildIfChanged()).isEqualTo(Outcome.THROTTLED);
        assertThat(syncAt(NOW.plusHours(1).minusNanos(1)).triggerRebuildIfChanged())
                .isEqualTo(Outcome.THROTTLED);
        assertThat(trigger.acceptedCalls).isEqualTo(1);
        assertThat(syncAt(NOW.plusHours(1)).triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);
        assertThat(trigger.acceptedCalls).isEqualTo(2);
    }

    @Test
    void acceptedHookWithLostResponseKeepsHourlyCooldownEvenAfterFailureIsRecorded() {
        state.setPublishedFingerprint("old-fingerprint");
        trigger.afterAccept = () -> {
            throw new FrontendRebuildTrigger.RebuildTriggerException("Deploy hook response lost");
        };

        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.FAILED);
        assertThat(state.getLastError()).isEqualTo("Deploy hook response lost");
        assertThat(trigger.acceptedCalls).isEqualTo(1);
        trigger.afterAccept = () -> {};

        assertThat(syncAt(NOW.plusMinutes(10)).triggerRebuildIfChanged()).isEqualTo(Outcome.THROTTLED);
        assertThat(trigger.acceptedCalls).isEqualTo(1);
        assertThat(syncAt(NOW.plusHours(1)).triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);
        assertThat(trigger.acceptedCalls).isEqualTo(2);
    }

    @Test
    void longerRetryIntervalCanExtendTheFailedAttemptCooldown() {
        trigger.failure = new FrontendRebuildTrigger.RebuildTriggerException("Deploy hook response lost");
        assertThat(syncAt(NOW, Duration.ofHours(2)).triggerRebuildIfChanged()).isEqualTo(Outcome.FAILED);
        trigger.failure = null;

        assertThat(syncAt(NOW.plusHours(1), Duration.ofHours(2)).triggerRebuildIfChanged())
                .isEqualTo(Outcome.BACKING_OFF);
        assertThat(trigger.calls).isEqualTo(1);
        assertThat(syncAt(NOW.plusHours(2), Duration.ofHours(2)).triggerRebuildIfChanged())
                .isEqualTo(Outcome.TRIGGERED);
    }

    @Test
    void lateSuccessCannotFinalizeANewerReplicasAttempt() {
        trigger.afterAccept = () -> {
            trigger.afterAccept = () -> {};
            when(directoryService.currentFingerprint()).thenReturn("later-fingerprint");
            assertThat(syncAt(NOW.plusHours(1)).triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);
        };

        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);

        assertThat(trigger.acceptedCalls).isEqualTo(2);
        assertThat(state.getPublishedFingerprint()).isEqualTo("later-fingerprint");
        assertThat(state.getLastHookAt()).isEqualTo(NOW.plusHours(1));
    }

    @Test
    void lateFailureCannotOverwriteANewerReplicasSuccess() {
        trigger.afterAccept = () -> {
            trigger.afterAccept = () -> {};
            assertThat(syncAt(NOW.plusHours(1)).triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);
            throw new FrontendRebuildTrigger.RebuildTriggerException("Delayed response lost");
        };

        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.FAILED);

        assertThat(trigger.acceptedCalls).isEqualTo(2);
        assertThat(state.getPublishedFingerprint()).isEqualTo("new-fingerprint");
        assertThat(state.getLastError()).isNull();
        assertThat(state.getLastHookAt()).isEqualTo(NOW.plusHours(1));
    }

    @Test
    void subMicrosecondClockStillFinalizesTheClaimAfterDatabaseTimestampPrecision() {
        when(syncStateRepository.save(any())).thenAnswer(invocation -> {
            PublicDirectorySyncState saved = invocation.getArgument(0);
            saved.setLastAttemptAt(saved.getLastAttemptAt().truncatedTo(ChronoUnit.MICROS));
            return saved;
        });

        assertThat(syncAt(NOW.plusNanos(999)).triggerRebuildIfChanged()).isEqualTo(Outcome.TRIGGERED);
        assertThat(state.getPublishedFingerprint()).isEqualTo("new-fingerprint");
        assertThat(state.getLastError()).isNull();
    }

    @Test
    void anotherReplicaHoldingTheRowSkipsThisPass() {
        when(syncStateRepository.lockSingleton()).thenReturn(Optional.empty());

        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.BUSY);
        assertThat(sync.assignMissingSlugs()).isZero();
        assertThat(trigger.calls).isZero();
        verify(clinicRepository, never()).findApprovedWithoutSlug();
    }

    @Test
    void noHookConfiguredMeansNoCall() {
        trigger.configured = false;

        assertThat(sync.triggerRebuildIfChanged()).isEqualTo(Outcome.NOT_CONFIGURED);
        verify(directoryService, never()).currentFingerprint();
    }

    @Test
    void newlyApprovedClinicsGetSlugsBeforeTheFingerprintIsTaken() {
        Clinic approved = Clinic.builder().id(9L).name("New Dental").city("Ryde").approved(true).enabled(true).build();
        when(clinicRepository.findApprovedWithoutSlug()).thenReturn(List.of(approved));

        assertThat(sync.synchronize()).isEqualTo(Outcome.TRIGGERED);

        var order = inOrder(slugService, clinicRepository, directoryService);
        order.verify(slugService).assignMissingSlug(eq(approved), any());
        order.verify(directoryService).currentFingerprint();
    }

    private PublicDirectorySyncService syncAt(LocalDateTime time) {
        return syncAt(time, Duration.ofMinutes(10));
    }

    private PublicDirectorySyncService syncAt(LocalDateTime time, Duration retryInterval) {
        PublicDirectoryProperties properties = new PublicDirectoryProperties(
                "/api/public/clinics", Duration.ofMinutes(5),
                new PublicDirectoryProperties.Sync(true, Duration.ofMinutes(5), Duration.ofMinutes(1)),
                new PublicDirectoryProperties.DeployHook("https://hooks.example/secret", Duration.ofHours(1),
                        retryInterval, Duration.ofSeconds(5), Duration.ofSeconds(10)));
        return new PublicDirectorySyncService(clinicRepository, syncStateRepository, slugService,
                directoryService, trigger, transactionManager,
                Clock.fixed(time.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), properties);
    }

    private static final class FakeTrigger implements FrontendRebuildTrigger {

        boolean configured = true;
        RebuildTriggerException failure;
        Runnable afterAccept = () -> {};
        int calls;
        int acceptedCalls;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void trigger() {
            calls++;
            if (failure != null) {
                throw failure;
            }
            acceptedCalls++;
            afterAccept.run();
        }
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {

        boolean failNextTransaction;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (failNextTransaction) {
                failNextTransaction = false;
                throw new IllegalStateException("Simulated database outage after hook acceptance");
            }
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
