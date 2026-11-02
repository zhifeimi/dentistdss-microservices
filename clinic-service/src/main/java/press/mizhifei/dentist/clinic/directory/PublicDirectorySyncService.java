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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 *       when it differs from the last confirmed hook payload or a previous
 *       attempt is unresolved, calls the Deploy Hook — at most one attempt per
 *       {@code min-interval} (default one hour), including failures.</li>
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
        /** Changed or unresolved, but the last attempt was less than min-interval ago. */
        THROTTLED,
        /** A failed/unresolved attempt is still within a longer retry-interval. */
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
            // Clinic timestamps are JVM-local; sync coordination timestamps are UTC.
            LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
            int assignedCount = 0;
            for (Clinic clinic : pending) {
                if (slugService.assignMissingSlug(clinic, now)) {
                    assignedCount++;
                }
            }
            return assignedCount;
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
            recordFailure(claim, ex.getMessage());
            log.warn("Frontend rebuild trigger failed ({}); retrying after both min-interval {} and retry-interval {}",
                    ex.getMessage(), minInterval, retryInterval);
            return Outcome.FAILED;
        }
        recordSuccess(claim);
        log.info("Triggered a frontend rebuild for a public clinic directory change");
        return Outcome.TRIGGERED;
    }

    /** A decided outcome or an attempt owned by its durable, microsecond-precision timestamp. */
    private record Claim(Outcome outcome, String fingerprint, LocalDateTime attemptedAt) {
    }

    private Claim claimAttempt() {
        Optional<PublicDirectorySyncState> locked = syncStateRepository.lockSingleton();
        if (locked.isEmpty()) {
            return new Claim(Outcome.BUSY, null, null);
        }
        PublicDirectorySyncState state = locked.get();
        String fingerprint = directoryService.currentFingerprint();
        if (fingerprint.equals(state.getPublishedFingerprint())) {
            return new Claim(Outcome.UP_TO_DATE, null, null);
        }
        LocalDateTime now = now().truncatedTo(ChronoUnit.MICROS);
        if ((state.getLastAttemptAt() != null && now.isBefore(state.getLastAttemptAt().plus(minInterval)))
                || (state.getLastHookAt() != null && now.isBefore(state.getLastHookAt().plus(minInterval)))) {
            return new Claim(Outcome.THROTTLED, null, null);
        }
        boolean lastAttemptFailed = state.getLastAttemptAt() != null
                && (state.getLastHookAt() == null || state.getLastAttemptAt().isAfter(state.getLastHookAt()));
        if (lastAttemptFailed && now.isBefore(state.getLastAttemptAt().plus(retryInterval))) {
            return new Claim(Outcome.BACKING_OFF, null, null);
        }
        // Commit the full cooldown and mark publication unknown before the POST:
        // acceptance followed by a crash must not hide a later return to the old payload.
        state.setPublishedFingerprint(null);
        state.setLastAttemptAt(now);
        syncStateRepository.save(state);
        return new Claim(null, fingerprint, now);
    }

    private void recordSuccess(Claim claim) {
        // A busy row remains unresolved; a delayed finalizer must not overwrite a newer claim.
        transactionTemplate.executeWithoutResult(status -> syncStateRepository
                .lockSingleton()
                .filter(state -> claim.attemptedAt().equals(state.getLastAttemptAt()))
                .ifPresent(state -> {
                    state.setPublishedFingerprint(claim.fingerprint());
                    state.setLastHookAt(claim.attemptedAt());
                    state.setLastError(null);
                    syncStateRepository.save(state);
                }));
    }

    private void recordFailure(Claim claim, String error) {
        String message = error == null ? "unknown error" : error;
        String truncated = message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
        transactionTemplate.executeWithoutResult(status -> syncStateRepository
                .lockSingleton()
                .filter(state -> claim.attemptedAt().equals(state.getLastAttemptAt()))
                .ifPresent(state -> {
                    state.setLastError(truncated);
                    syncStateRepository.save(state);
                }));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
