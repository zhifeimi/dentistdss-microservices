package press.mizhifei.dentist.clinic.directory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.model.PublicDirectorySyncState;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.clinic.repository.PublicDirectorySyncStateRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Keeps the frontend's prerendered clinic pages in step with the database.
 *
 * <p>Approvals and deletions happen in auth-service, edits and delisting in
 * clinic-service, so instead of hooking every write path this reconciler
 * compares the public payload itself: each run it
 * <ol>
 *   <li>assigns slugs to newly approved clinics, then</li>
 *   <li>fingerprints the public directory (the same value as its ETag) and,
 *       when it differs from the one last published, calls the Deploy Hook —
 *       at most once per {@code min-interval} (default one hour), so a burst
 *       of edits causes one rebuild, not one per edit.</li>
 * </ol>
 * Edits to fields that are not public (email, staff) never trigger a rebuild.
 *
 * <p>Replicas coordinate through the {@code public_directory_sync} row,
 * locked with {@code FOR UPDATE SKIP LOCKED}; the hook call itself runs
 * outside any transaction, after the attempt is claimed.
 */
@Slf4j
@Service
public class PublicDirectorySyncService {

    private static final int MAX_ERROR_LENGTH = 255;

    public enum Outcome {
        /** No deploy hook URL configured. */
        NOT_CONFIGURED,
        /** Another replica holds the coordination row. */
        BUSY,
        /** The published pages already match the data. */
        UP_TO_DATE,
        /** Changed, but the last rebuild was triggered less than min-interval ago. */
        THROTTLED,
        /** Changed, but the last attempt failed less than retry-interval ago. */
        BACKING_OFF,
        TRIGGERED,
        FAILED
    }

    private final ClinicRepository clinicRepository;
    private final PublicDirectorySyncStateRepository syncStateRepository;
    private final ClinicSlugService slugService;
    private final PublicClinicDirectoryService directoryService;
    private final FrontendRebuildTrigger rebuildTrigger;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration minInterval;
    private final Duration retryInterval;

    public PublicDirectorySyncService(
            ClinicRepository clinicRepository,
            PublicDirectorySyncStateRepository syncStateRepository,
            ClinicSlugService slugService,
            PublicClinicDirectoryService directoryService,
            FrontendRebuildTrigger rebuildTrigger,
            PlatformTransactionManager transactionManager,
            Clock clock,
            PublicDirectoryProperties properties) {
        this.clinicRepository = clinicRepository;
        this.syncStateRepository = syncStateRepository;
        this.slugService = slugService;
        this.directoryService = directoryService;
        this.rebuildTrigger = rebuildTrigger;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.minInterval = properties.deployHook().minInterval();
        this.retryInterval = properties.deployHook().retryInterval();
    }

    /** One reconciler pass: slugs first, so a newly approved clinic is in the fingerprint. */
    public Outcome synchronize() {
        assignMissingSlugs();
        return triggerRebuildIfChanged();
    }

    /** @return how many clinics received a slug (0 when another replica is busy) */
    public int assignMissingSlugs() {
        Integer assigned = transactionTemplate.execute(status -> {
            if (syncStateRepository.lockSingleton().isEmpty()) {
                return 0;
            }
            List<Clinic> pending = clinicRepository.findApprovedWithoutSlug();
            LocalDateTime now = now();
            for (Clinic clinic : pending) {
                slugService.refreshSlug(clinic, now);
                // Flush per clinic so the next allocation sees this slug as taken.
                clinicRepository.saveAndFlush(clinic);
            }
            return pending.size();
        });
        return assigned == null ? 0 : assigned;
    }

    public Outcome triggerRebuildIfChanged() {
        if (!rebuildTrigger.isConfigured()) {
            return Outcome.NOT_CONFIGURED;
        }
        Claim claim = transactionTemplate.execute(status -> claimAttempt());
        if (claim == null || claim.outcome() != null) {
            return claim == null ? Outcome.BUSY : claim.outcome();
        }

        try {
            rebuildTrigger.trigger();
        } catch (FrontendRebuildTrigger.RebuildTriggerException ex) {
            recordFailure(ex.getMessage());
            log.warn("Frontend rebuild trigger failed ({}); retrying in {} at the earliest",
                    ex.getMessage(), retryInterval);
            return Outcome.FAILED;
        }
        recordSuccess(claim.fingerprint());
        log.info("Triggered a frontend rebuild for a public clinic directory change");
        return Outcome.TRIGGERED;
    }

    /** Either a decided outcome (nothing to do) or a claimed attempt for {@code fingerprint}. */
    private record Claim(Outcome outcome, String fingerprint) {
    }

    private Claim claimAttempt() {
        Optional<PublicDirectorySyncState> locked = syncStateRepository.lockSingleton();
        if (locked.isEmpty()) {
            return new Claim(Outcome.BUSY, null);
        }
        PublicDirectorySyncState state = locked.get();
        String fingerprint = directoryService.currentFingerprint();
        if (fingerprint.equals(state.getPublishedFingerprint())) {
            return new Claim(Outcome.UP_TO_DATE, null);
        }
        LocalDateTime now = now();
        if (state.getLastHookAt() != null && now.isBefore(state.getLastHookAt().plus(minInterval))) {
            return new Claim(Outcome.THROTTLED, null);
        }
        boolean lastAttemptFailed = state.getLastAttemptAt() != null
                && (state.getLastHookAt() == null || state.getLastAttemptAt().isAfter(state.getLastHookAt()));
        if (lastAttemptFailed && now.isBefore(state.getLastAttemptAt().plus(retryInterval))) {
            return new Claim(Outcome.BACKING_OFF, null);
        }
        // Claiming the attempt before the call keeps other replicas (and a
        // restart mid-call) from calling the hook again within retry-interval.
        state.setLastAttemptAt(now);
        syncStateRepository.save(state);
        return new Claim(null, fingerprint);
    }

    private void recordSuccess(String fingerprint) {
        transactionTemplate.executeWithoutResult(status -> syncStateRepository
                .findById(PublicDirectorySyncState.SINGLETON_ID)
                .ifPresent(state -> {
                    state.setPublishedFingerprint(fingerprint);
                    state.setLastHookAt(state.getLastAttemptAt() != null ? state.getLastAttemptAt() : now());
                    state.setLastError(null);
                    syncStateRepository.save(state);
                }));
    }

    private void recordFailure(String error) {
        String message = error == null ? "unknown error" : error;
        String truncated = message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
        transactionTemplate.executeWithoutResult(status -> syncStateRepository
                .findById(PublicDirectorySyncState.SINGLETON_ID)
                .ifPresent(state -> {
                    state.setLastError(truncated);
                    syncStateRepository.save(state);
                }));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
