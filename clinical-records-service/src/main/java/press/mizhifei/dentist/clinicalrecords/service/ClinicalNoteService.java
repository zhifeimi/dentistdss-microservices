package press.mizhifei.dentist.clinicalrecords.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.clinicalrecords.client.AuthServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.ClinicServiceClient;
import press.mizhifei.dentist.clinicalrecords.dto.ClinicalNoteRequest;
import press.mizhifei.dentist.clinicalrecords.dto.ClinicalNoteResponse;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalResourceNotFoundException;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalStateConflictException;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;
import press.mizhifei.dentist.clinicalrecords.model.ClinicalNote;
import press.mizhifei.dentist.clinicalrecords.model.ServiceVisit;
import press.mizhifei.dentist.clinicalrecords.repository.ClinicalNoteRepository;
import press.mizhifei.dentist.clinicalrecords.repository.ServiceVisitRepository;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsAccess;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClinicalNoteService {

    private final ClinicalNoteRepository clinicalNoteRepository;
    private final ServiceVisitRepository serviceVisitRepository;
    private final AuthServiceClient authServiceClient;
    private final ClinicServiceClient clinicServiceClient;

    @Transactional
    public ClinicalNoteResponse createClinicalNote(
            ClinicalRecordsActor actor,
            ClinicalNoteRequest request) {
        ClinicalRecordsAccess.WriteOwner owner = ClinicalRecordsAccess.resolveWriteOwner(
                actor,
                request.getPatientId(),
                request.getDentistId(),
                request.getClinicId());

        Integer version = 1;
        if (request.getParentNoteId() != null) {
            long parentNoteId = ClinicalRecordsAccess.requirePositive(request.getParentNoteId());
            ClinicalNote parent = findManageableNote(actor, parentNoteId);
            requireMatchingOwner(parent, owner);
            List<ClinicalNote> versions = actor.isSystemAdmin()
                    ? clinicalNoteRepository.findNoteVersions(parentNoteId)
                    : clinicalNoteRepository.findNoteVersionsByDentistIdAndClinicId(
                            parentNoteId,
                            actor.userId(),
                            actor.requiredClinicId());
            version = versions.isEmpty() ? 2 : versions.get(0).getVersion() + 1;
        }

        if (request.getVisitId() != null) {
            ServiceVisit visit = findManageableVisit(actor, request.getVisitId());
            requireMatchingOwner(visit, owner);
        }

        ClinicalNote clinicalNote = ClinicalNote.builder()
                .appointmentId(request.getAppointmentId())
                .patientId(owner.patientId())
                .dentistId(owner.dentistId())
                .clinicId(owner.clinicId())
                .visitId(request.getVisitId())
                .chiefComplaint(request.getChiefComplaint())
                .examinationFindings(request.getExaminationFindings())
                .diagnosis(request.getDiagnosis())
                .treatmentPerformed(request.getTreatmentPerformed())
                .treatmentPlan(request.getTreatmentPlan())
                .prescriptions(request.getPrescriptions())
                .followUpInstructions(request.getFollowUpInstructions())
                .aiAssistedNotes(request.getAiAssistedNotes())
                .attachments(request.getAttachments())
                .category(request.getCategory())
                .isDraft(true)
                .version(version)
                .parentNoteId(request.getParentNoteId())
                .build();

        ClinicalNote saved = clinicalNoteRepository.save(clinicalNote);
        log.info("Created clinical note {}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public ClinicalNoteResponse updateClinicalNote(
            ClinicalRecordsActor actor,
            Long noteId,
            ClinicalNoteRequest request) {
        ClinicalNote clinicalNote = findManageableNote(actor, noteId);
        if (!Boolean.TRUE.equals(clinicalNote.getIsDraft())) {
            throw new ClinicalStateConflictException();
        }
        requireImmutableFieldsMatch(clinicalNote, request);

        clinicalNote.setChiefComplaint(request.getChiefComplaint());
        clinicalNote.setExaminationFindings(request.getExaminationFindings());
        clinicalNote.setDiagnosis(request.getDiagnosis());
        clinicalNote.setTreatmentPerformed(request.getTreatmentPerformed());
        clinicalNote.setTreatmentPlan(request.getTreatmentPlan());
        clinicalNote.setPrescriptions(request.getPrescriptions());
        clinicalNote.setFollowUpInstructions(request.getFollowUpInstructions());
        clinicalNote.setAiAssistedNotes(request.getAiAssistedNotes());
        clinicalNote.setAttachments(request.getAttachments());
        clinicalNote.setCategory(request.getCategory());
        clinicalNote.setIsDraft(true);

        ClinicalNote saved = clinicalNoteRepository.save(clinicalNote);
        log.info("Updated clinical note {}", noteId);
        return toResponse(saved);
    }

    @Transactional
    public ClinicalNoteResponse signClinicalNote(ClinicalRecordsActor actor, Long noteId) {
        ClinicalRecordsAccess.requireDentistSigner(actor);
        long targetNoteId = ClinicalRecordsAccess.requirePositive(noteId);
        ClinicalNote clinicalNote = clinicalNoteRepository.findByIdAndDentistIdAndClinicId(
                        targetNoteId,
                        actor.userId(),
                        actor.requiredClinicId())
                .orElseThrow(ClinicalResourceNotFoundException::new);

        if (!Boolean.TRUE.equals(clinicalNote.getIsDraft())) {
            throw new ClinicalStateConflictException();
        }

        clinicalNote.setIsDraft(false);
        clinicalNote.setSignedAt(LocalDateTime.now());
        clinicalNote.setSignedBy(actor.userId());

        ClinicalNote saved = clinicalNoteRepository.save(clinicalNote);
        log.info("Signed clinical note {}", noteId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ClinicalNoteResponse getClinicalNote(ClinicalRecordsActor actor, Long noteId) {
        return toResponse(findReadableNote(actor, noteId));
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteResponse> getClinicClinicalNotes(
            ClinicalRecordsActor actor,
            Long clinicId) {
        requireSystemAdmin(actor);
        return clinicalNoteRepository.findByClinicIdOrderByCreatedAtDesc(
                        ClinicalRecordsAccess.requirePositive(clinicId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteResponse> getPatientClinicalNotes(
            ClinicalRecordsActor actor,
            Long patientId,
            boolean includeDrafts) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        List<ClinicalNote> notes;
        if (actor.isSystemAdmin()) {
            notes = includeDrafts
                    ? clinicalNoteRepository.findByPatientIdOrderByCreatedAtDesc(targetPatientId)
                    : clinicalNoteRepository.findSignedNotesByPatientId(targetPatientId);
        } else if (ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            notes = clinicalNoteRepository.findSignedNotesByPatientId(targetPatientId);
        } else if (actor.isDentist()) {
            notes = includeDrafts
                    ? clinicalNoteRepository.findByPatientIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
                            targetPatientId,
                            actor.userId(),
                            actor.requiredClinicId())
                    : clinicalNoteRepository.findSignedNotesByPatientIdAndDentistIdAndClinicId(
                            targetPatientId,
                            actor.userId(),
                            actor.requiredClinicId());
        } else {
            throw new AccessDeniedException("Clinical read access is unavailable");
        }
        return notes.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteResponse> getDentistClinicalNotes(
            ClinicalRecordsActor actor,
            Long dentistId) {
        long targetDentistId = ClinicalRecordsAccess.requirePositive(dentistId);
        List<ClinicalNote> notes;
        if (actor.isSystemAdmin()) {
            notes = clinicalNoteRepository.findByDentistIdOrderByCreatedAtDesc(targetDentistId);
        } else if (actor.isDentist() && actor.userId() == targetDentistId) {
            notes = clinicalNoteRepository.findByDentistIdAndClinicIdOrderByCreatedAtDesc(
                    targetDentistId,
                    actor.requiredClinicId());
        } else if (actor.isDentist()) {
            throw new ClinicalResourceNotFoundException();
        } else {
            throw new AccessDeniedException("Clinical read access is unavailable");
        }
        return notes.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteResponse> getDentistDraftNotes(
            ClinicalRecordsActor actor,
            Long dentistId) {
        long targetDentistId = ClinicalRecordsAccess.requirePositive(dentistId);
        List<ClinicalNote> notes;
        if (actor.isSystemAdmin()) {
            notes = clinicalNoteRepository.findDraftNotesByDentistId(targetDentistId);
        } else if (actor.isDentist() && actor.userId() == targetDentistId) {
            notes = clinicalNoteRepository.findDraftNotesByDentistIdAndClinicId(
                    targetDentistId,
                    actor.requiredClinicId());
        } else if (actor.isDentist()) {
            throw new ClinicalResourceNotFoundException();
        } else {
            throw new AccessDeniedException("Clinical read access is unavailable");
        }
        return notes.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ClinicalNoteResponse getClinicalNoteByAppointment(
            ClinicalRecordsActor actor,
            Long appointmentId) {
        long targetAppointmentId = ClinicalRecordsAccess.requirePositive(appointmentId);
        Optional<ClinicalNote> note;
        if (actor.isSystemAdmin()) {
            note = clinicalNoteRepository.findByAppointmentId(targetAppointmentId);
        } else {
            note = Optional.empty();
            if (actor.isPatient()) {
                note = clinicalNoteRepository.findByAppointmentIdAndPatientIdAndIsDraftFalse(
                        targetAppointmentId,
                        actor.userId());
            }
            if (note.isEmpty() && actor.isDentist()) {
                note = clinicalNoteRepository.findByAppointmentIdAndDentistIdAndClinicId(
                        targetAppointmentId,
                        actor.userId(),
                        actor.requiredClinicId());
            }
            if (!actor.isPatient() && !actor.isDentist()) {
                throw new AccessDeniedException("Clinical read access is unavailable");
            }
        }
        return toResponse(note.orElseThrow(ClinicalResourceNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteResponse> getVisitClinicalNotes(
            ClinicalRecordsActor actor,
            Long visitId) {
        long targetVisitId = ClinicalRecordsAccess.requirePositive(visitId);
        List<ClinicalNote> notes;
        if (actor.isSystemAdmin()) {
            notes = clinicalNoteRepository.findByVisitIdOrderByCreatedAtDesc(targetVisitId);
        } else if (actor.isPatient()) {
            notes = clinicalNoteRepository.findByVisitIdAndPatientIdAndIsDraftFalseOrderByCreatedAtDesc(
                    targetVisitId,
                    actor.userId());
        } else if (actor.isDentist()) {
            notes = clinicalNoteRepository.findByVisitIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
                    targetVisitId,
                    actor.userId(),
                    actor.requiredClinicId());
        } else {
            throw new AccessDeniedException("Clinical read access is unavailable");
        }
        return notes.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteResponse> getNoteVersions(
            ClinicalRecordsActor actor,
            Long parentNoteId) {
        long targetParentNoteId = ClinicalRecordsAccess.requirePositive(parentNoteId);
        ClinicalNote parent = findReadableNote(actor, targetParentNoteId);
        List<ClinicalNote> notes;
        if (actor.isSystemAdmin()) {
            notes = clinicalNoteRepository.findNoteVersions(targetParentNoteId);
        } else if (ClinicalRecordsAccess.matchesPatient(actor, parent.getPatientId())) {
            notes = clinicalNoteRepository.findSignedNoteVersionsByPatientId(targetParentNoteId, actor.userId());
        } else if (ClinicalRecordsAccess.matchesDentist(actor, parent.getDentistId(), parent.getClinicId())) {
            notes = clinicalNoteRepository.findNoteVersionsByDentistIdAndClinicId(
                    targetParentNoteId,
                    actor.userId(),
                    actor.requiredClinicId());
        } else {
            throw new ClinicalResourceNotFoundException();
        }
        return notes.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteResponse> searchPatientNotes(
            ClinicalRecordsActor actor,
            Long patientId,
            String searchTerm) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        if (searchTerm == null || searchTerm.isBlank()) {
            throw new InvalidClinicalRequestException();
        }
        List<ClinicalNote> notes;
        if (actor.isSystemAdmin()) {
            notes = clinicalNoteRepository.searchNotesByPatient(targetPatientId, searchTerm);
        } else if (ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            notes = clinicalNoteRepository.searchSignedNotesByPatient(targetPatientId, searchTerm);
        } else if (actor.isDentist()) {
            notes = clinicalNoteRepository.searchNotesByPatientAndDentistIdAndClinicId(
                    targetPatientId,
                    actor.userId(),
                    actor.requiredClinicId(),
                    searchTerm);
        } else {
            throw new AccessDeniedException("Clinical read access is unavailable");
        }
        return notes.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteResponse> getPatientNotesByCategory(
            ClinicalRecordsActor actor,
            Long patientId,
            String category) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        if (category == null || category.isBlank()) {
            throw new InvalidClinicalRequestException();
        }
        List<ClinicalNote> notes;
        if (actor.isSystemAdmin()) {
            notes = clinicalNoteRepository.findByPatientIdAndCategoryOrderByCreatedAtDesc(targetPatientId, category);
        } else if (ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            notes = clinicalNoteRepository.findSignedNotesByPatientIdAndCategory(targetPatientId, category);
        } else if (actor.isDentist()) {
            notes = clinicalNoteRepository.findByPatientIdAndDentistIdAndClinicIdAndCategory(
                    targetPatientId,
                    actor.userId(),
                    actor.requiredClinicId(),
                    category);
        } else {
            throw new AccessDeniedException("Clinical read access is unavailable");
        }
        return notes.stream().map(this::toResponse).toList();
    }

    private ClinicalNote findReadableNote(ClinicalRecordsActor actor, Long noteId) {
        long targetNoteId = ClinicalRecordsAccess.requirePositive(noteId);
        Optional<ClinicalNote> note;
        if (actor.isSystemAdmin()) {
            note = clinicalNoteRepository.findById(targetNoteId);
        } else {
            note = Optional.empty();
            if (actor.isPatient()) {
                note = clinicalNoteRepository.findByIdAndPatientIdAndIsDraftFalse(targetNoteId, actor.userId());
            }
            if (note.isEmpty() && actor.isDentist()) {
                note = clinicalNoteRepository.findByIdAndDentistIdAndClinicId(
                        targetNoteId,
                        actor.userId(),
                        actor.requiredClinicId());
            }
            if (!actor.isPatient() && !actor.isDentist()) {
                throw new AccessDeniedException("Clinical read access is unavailable");
            }
        }
        return note.orElseThrow(ClinicalResourceNotFoundException::new);
    }

    private ClinicalNote findManageableNote(ClinicalRecordsActor actor, Long noteId) {
        long targetNoteId = ClinicalRecordsAccess.requirePositive(noteId);
        ClinicalRecordsAccess.requireClinicalManager(actor);
        Optional<ClinicalNote> note = actor.isSystemAdmin()
                ? clinicalNoteRepository.findById(targetNoteId)
                : clinicalNoteRepository.findByIdAndDentistIdAndClinicId(
                        targetNoteId,
                        actor.userId(),
                        actor.requiredClinicId());
        return note.orElseThrow(ClinicalResourceNotFoundException::new);
    }

    private ServiceVisit findManageableVisit(ClinicalRecordsActor actor, Long visitId) {
        long targetVisitId = ClinicalRecordsAccess.requirePositive(visitId);
        ClinicalRecordsAccess.requireClinicalManager(actor);
        Optional<ServiceVisit> visit = actor.isSystemAdmin()
                ? serviceVisitRepository.findById(targetVisitId)
                : serviceVisitRepository.findByIdAndDentistIdAndClinicId(
                        targetVisitId,
                        actor.userId(),
                        actor.requiredClinicId());
        return visit.orElseThrow(ClinicalResourceNotFoundException::new);
    }

    private void requireMatchingOwner(
            ClinicalNote note,
            ClinicalRecordsAccess.WriteOwner owner) {
        if (!Objects.equals(note.getPatientId(), owner.patientId())
                || !Objects.equals(note.getDentistId(), owner.dentistId())
                || !Objects.equals(note.getClinicId(), owner.clinicId())) {
            throw new InvalidClinicalRequestException();
        }
    }

    private void requireMatchingOwner(
            ServiceVisit visit,
            ClinicalRecordsAccess.WriteOwner owner) {
        if (!Objects.equals(visit.getPatientId(), owner.patientId())
                || !Objects.equals(visit.getDentistId(), owner.dentistId())
                || !Objects.equals(visit.getClinicId(), owner.clinicId())) {
            throw new InvalidClinicalRequestException();
        }
    }

    private void requireImmutableFieldsMatch(ClinicalNote note, ClinicalNoteRequest request) {
        if ((request.getPatientId() != null && !Objects.equals(note.getPatientId(), request.getPatientId()))
                || (request.getDentistId() != null && !Objects.equals(note.getDentistId(), request.getDentistId()))
                || (request.getClinicId() != null && !Objects.equals(note.getClinicId(), request.getClinicId()))
                || (request.getAppointmentId() != null
                        && !Objects.equals(note.getAppointmentId(), request.getAppointmentId()))
                || (request.getVisitId() != null && !Objects.equals(note.getVisitId(), request.getVisitId()))
                || (request.getParentNoteId() != null
                        && !Objects.equals(note.getParentNoteId(), request.getParentNoteId()))) {
            throw new InvalidClinicalRequestException();
        }
    }

    private void requireSystemAdmin(ClinicalRecordsActor actor) {
        if (!actor.isSystemAdmin()) {
            throw new AccessDeniedException("Clinical administrative access is unavailable");
        }
    }

    private ClinicalNoteResponse toResponse(ClinicalNote clinicalNote) {
        ClinicalNoteResponse response = ClinicalNoteResponse.builder()
                .id(clinicalNote.getId())
                .appointmentId(clinicalNote.getAppointmentId())
                .patientId(clinicalNote.getPatientId())
                .dentistId(clinicalNote.getDentistId())
                .clinicId(clinicalNote.getClinicId())
                .visitId(clinicalNote.getVisitId())
                .chiefComplaint(clinicalNote.getChiefComplaint())
                .examinationFindings(clinicalNote.getExaminationFindings())
                .diagnosis(clinicalNote.getDiagnosis())
                .treatmentPerformed(clinicalNote.getTreatmentPerformed())
                .treatmentPlan(clinicalNote.getTreatmentPlan())
                .prescriptions(clinicalNote.getPrescriptions())
                .followUpInstructions(clinicalNote.getFollowUpInstructions())
                .aiAssistedNotes(clinicalNote.getAiAssistedNotes())
                .attachments(clinicalNote.getAttachments())
                .category(clinicalNote.getCategory())
                .isDraft(clinicalNote.getIsDraft())
                .version(clinicalNote.getVersion())
                .parentNoteId(clinicalNote.getParentNoteId())
                .createdAt(clinicalNote.getCreatedAt())
                .updatedAt(clinicalNote.getUpdatedAt())
                .signedAt(clinicalNote.getSignedAt())
                .signedBy(clinicalNote.getSignedBy())
                .build();

        try {
            response.setPatientName(authServiceClient.getUserFullName(clinicalNote.getPatientId()));
        } catch (Exception exception) {
            log.warn("Failed to enrich clinical note {} from a dependent service", clinicalNote.getId());
        }
        try {
            response.setDentistName(authServiceClient.getUserFullName(clinicalNote.getDentistId()));
        } catch (Exception exception) {
            log.warn("Failed to enrich clinical note {} from a dependent service", clinicalNote.getId());
        }
        if (clinicalNote.getSignedBy() != null) {
            try {
                response.setSignedByName(authServiceClient.getUserFullName(clinicalNote.getSignedBy()));
            } catch (Exception exception) {
                log.warn("Failed to enrich clinical note {} from a dependent service", clinicalNote.getId());
            }
        }
        try {
            response.setClinicName(clinicServiceClient.getClinic(clinicalNote.getClinicId()).getName());
        } catch (Exception exception) {
            log.warn("Failed to enrich clinical note {} from a dependent service", clinicalNote.getId());
        }
        return response;
    }
}
