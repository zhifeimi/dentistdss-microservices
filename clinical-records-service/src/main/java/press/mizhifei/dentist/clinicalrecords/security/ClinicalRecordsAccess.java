package press.mizhifei.dentist.clinicalrecords.security;

import org.springframework.security.access.AccessDeniedException;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;

import java.util.Objects;

public final class ClinicalRecordsAccess {

    private ClinicalRecordsAccess() {
    }

    public static WriteOwner resolveWriteOwner(
            ClinicalRecordsActor actor,
            Long patientId,
            Long requestedDentistId,
            Long requestedClinicId) {
        long requiredPatientId = requirePositive(patientId);

        if (actor.isSystemAdmin()) {
            return new WriteOwner(
                    requiredPatientId,
                    requirePositive(requestedDentistId),
                    requirePositive(requestedClinicId));
        }

        if (!actor.isDentist()) {
            throw new AccessDeniedException("Clinical maintenance access is unavailable");
        }

        long clinicId = actor.requiredClinicId();
        if ((requestedDentistId != null && !Objects.equals(requestedDentistId, actor.userId()))
                || (requestedClinicId != null && !Objects.equals(requestedClinicId, clinicId))) {
            throw new InvalidClinicalRequestException();
        }
        return new WriteOwner(requiredPatientId, actor.userId(), clinicId);
    }

    public static boolean matchesPatient(ClinicalRecordsActor actor, Long patientId) {
        return actor.isPatient() && Objects.equals(patientId, actor.userId());
    }

    public static boolean matchesDentist(
            ClinicalRecordsActor actor,
            Long dentistId,
            Long clinicId) {
        return actor.isDentist()
                && Objects.equals(dentistId, actor.userId())
                && Objects.equals(clinicId, actor.requiredClinicId());
    }

    public static void requireClinicalManager(ClinicalRecordsActor actor) {
        if (actor.isSystemAdmin()) {
            return;
        }
        if (!actor.isDentist()) {
            throw new AccessDeniedException("Clinical maintenance access is unavailable");
        }
        actor.requiredClinicId();
    }

    public static void requireDentistSigner(ClinicalRecordsActor actor) {
        if (actor.isSystemAdmin() || !actor.isDentist()) {
            throw new AccessDeniedException("Clinical signing access is unavailable");
        }
        actor.requiredClinicId();
    }

    public static void requirePatientAcceptance(ClinicalRecordsActor actor) {
        if (actor.isSystemAdmin() || !actor.isPatient()) {
            throw new AccessDeniedException("Treatment-plan acceptance access is unavailable");
        }
    }

    public static long requirePositive(Long value) {
        if (value == null || value <= 0) {
            throw new InvalidClinicalRequestException();
        }
        return value;
    }

    public record WriteOwner(long patientId, long dentistId, long clinicId) {
    }
}
