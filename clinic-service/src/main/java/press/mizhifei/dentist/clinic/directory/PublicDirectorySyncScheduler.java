package press.mizhifei.dentist.clinic.directory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the public directory reconciler on a fixed delay. */
@Slf4j
@Component
@ConditionalOnProperty(name = "public-directory.sync.enabled", havingValue = "true", matchIfMissing = true)
public class PublicDirectorySyncScheduler {

    private final PublicDirectorySyncService syncService;

    public PublicDirectorySyncScheduler(PublicDirectorySyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(
            initialDelayString = "${public-directory.sync.initial-delay:PT1M}",
            fixedDelayString = "${public-directory.sync.interval:PT5M}")
    public void synchronize() {
        try {
            PublicDirectorySyncService.Outcome outcome = syncService.synchronize();
            log.debug("Public clinic directory sync: {}", outcome);
        } catch (RuntimeException ex) {
            // Never let one failed pass stop the schedule; the next pass retries.
            log.error("Public clinic directory sync failed: {}", ex.getClass().getSimpleName(), ex);
        }
    }
}
