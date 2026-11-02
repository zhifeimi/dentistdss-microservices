package press.mizhifei.dentist.clinic.directory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.model.ClinicSlugHistory;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.clinic.repository.ClinicSlugHistoryRepository;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Assigns and maintains public clinic slugs. A slug is unique across every
 * clinic's current AND previous slugs, so an old URL can only ever redirect
 * to the clinic that used it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClinicSlugService {

    private static final int MAX_ATTEMPTS = 1000;

    private final ClinicRepository clinicRepository;
    private final ClinicSlugHistoryRepository slugHistoryRepository;

    /** Assign without mutating the selected entity or normalizing unrelated existing fields. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean assignMissingSlug(Clinic clinic, LocalDateTime now) {
        String slug = allocate(ClinicSlugs.baseSlug(clinic.getName(), clinic.getCity()), clinic.getId());
        return clinicRepository.assignSlugIfMissing(clinic.getId(), slug, now) > 0;
    }

    /**
     * Gives an approved clinic its slug, or moves it to a new one after a
     * rename or a move to another suburb (the old slug goes to the history).
     * A clinic whose name and suburb still produce its current slug keeps it,
     * including a collision-suffixed one such as {@code smile-dental-2}.
     * Changes the entity in memory; the caller saves it.
     *
     * @return whether the clinic's slug changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean refreshSlug(Clinic clinic, LocalDateTime now) {
        String base = ClinicSlugs.baseSlug(clinic.getName(), clinic.getCity());
        String current = clinic.getSlug();
        if (Objects.equals(current, base)
                || (ClinicSlugs.isCandidateOf(current, base)
                    && slugHistoryRepository.findByClinicIdOrderByRetiredAtDescSlugAsc(clinic.getId()).stream()
                        .noneMatch(previous -> ClinicSlugs.isCandidateOf(previous.getSlug(), base)))) {
            return false;
        }

        String next = allocate(base, clinic.getId());
        if (Objects.equals(current, next)) {
            return false;
        }
        if (current != null) {
            slugHistoryRepository.save(ClinicSlugHistory.builder()
                    .slug(current)
                    .clinicId(clinic.getId())
                    .retiredAt(now)
                    .build());
        }
        // Renaming back to an earlier name reclaims that slug from the history.
        slugHistoryRepository.findById(next)
                .filter(previous -> Objects.equals(previous.getClinicId(), clinic.getId()))
                .ifPresent(slugHistoryRepository::delete);
        clinic.setSlug(next);
        log.info("Clinic {} public slug is now {}{}", clinic.getId(), next,
                current == null ? "" : " (was " + current + ")");
        return true;
    }

    private String allocate(String base, Long clinicId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String candidate = ClinicSlugs.candidate(base, attempt);
            if (isAvailableFor(candidate, clinicId)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free public slug for base " + base);
    }

    private boolean isAvailableFor(String slug, Long clinicId) {
        boolean usedByAnotherClinic = clinicId == null
                ? clinicRepository.existsBySlug(slug)
                : clinicRepository.existsBySlugAndIdNot(slug, clinicId);
        if (usedByAnotherClinic) {
            return false;
        }
        return slugHistoryRepository.findById(slug)
                .map(previous -> Objects.equals(previous.getClinicId(), clinicId))
                .orElse(true);
    }
}
