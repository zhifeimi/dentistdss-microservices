package press.mizhifei.dentist.clinicalrecords.service;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import press.mizhifei.dentist.clinicalrecords.client.AuthServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.ClinicServiceClient;
import press.mizhifei.dentist.clinicalrecords.config.FileUploadConfig;
import press.mizhifei.dentist.clinicalrecords.dto.DentalImageResponse;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalDependencyUnavailableException;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalResourceNotFoundException;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;
import press.mizhifei.dentist.clinicalrecords.model.ClinicalNote;
import press.mizhifei.dentist.clinicalrecords.model.DentalImage;
import press.mizhifei.dentist.clinicalrecords.model.ServiceVisit;
import press.mizhifei.dentist.clinicalrecords.repository.ClinicalNoteRepository;
import press.mizhifei.dentist.clinicalrecords.repository.DentalImageRepository;
import press.mizhifei.dentist.clinicalrecords.repository.ServiceVisitRepository;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsAccess;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DentalImageService {

    private final DentalImageRepository dentalImageRepository;
    private final ClinicalNoteRepository clinicalNoteRepository;
    private final ServiceVisitRepository serviceVisitRepository;
    private final AuthServiceClient authServiceClient;
    private final ClinicServiceClient clinicServiceClient;
    private final GridFSBucket gridFSBucket;
    @Qualifier("thumbnailGridFSBucket")
    private final GridFSBucket thumbnailGridFSBucket;
    private final FileUploadConfig fileUploadConfig;

    @Transactional
    public DentalImageResponse uploadDentalImage(
            ClinicalRecordsActor actor,
            MultipartFile file,
            Long patientId,
            Long requestedDentistId,
            Long requestedClinicId,
            Long clinicalNoteId,
            Long visitId,
            String imageType,
            String toothNumber,
            String description,
            String tags) {
        ClinicalRecordsAccess.WriteOwner owner = ClinicalRecordsAccess.resolveWriteOwner(
                actor,
                patientId,
                requestedDentistId,
                requestedClinicId);
        validateLinks(actor, owner, clinicalNoteId, visitId);
        validateImageFile(file, imageType);

        String originalFilename = safeOriginalFilename(file);
        ObjectId originalFileId = uploadOriginal(file, owner, imageType, originalFilename);
        String thumbnailFileId = uploadThumbnail(file, owner, originalFileId, originalFilename);

        DentalImage dentalImage = DentalImage.builder()
                .patientId(owner.patientId())
                .dentistId(owner.dentistId())
                .clinicId(owner.clinicId())
                .clinicalNoteId(clinicalNoteId)
                .visitId(visitId)
                .gridfsFileId(originalFileId.toString())
                .thumbnailGridfsId(thumbnailFileId)
                .originalFilename(originalFilename)
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .imageType(imageType)
                .toothNumber(toothNumber)
                .description(description)
                .tags(tags)
                .build();

        DentalImage saved = dentalImageRepository.save(dentalImage);
        log.info("Uploaded dental image {}", saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public InputStream downloadDentalImage(ClinicalRecordsActor actor, Long imageId) {
        DentalImage dentalImage = findReadableImage(actor, imageId);
        try {
            return gridFSBucket.openDownloadStream(parseStoredObjectId(dentalImage.getGridfsFileId()));
        } catch (RuntimeException exception) {
            log.warn("Clinical image {} storage download failed", dentalImage.getId());
            throw new ClinicalDependencyUnavailableException(exception);
        }
    }

    @Transactional(readOnly = true)
    public InputStream downloadThumbnail(ClinicalRecordsActor actor, Long imageId) {
        DentalImage dentalImage = findReadableImage(actor, imageId);
        if (dentalImage.getThumbnailGridfsId() == null) {
            throw new ClinicalResourceNotFoundException();
        }
        try {
            return thumbnailGridFSBucket.openDownloadStream(
                    parseStoredObjectId(dentalImage.getThumbnailGridfsId()));
        } catch (RuntimeException exception) {
            log.warn("Clinical image {} thumbnail download failed", dentalImage.getId());
            throw new ClinicalDependencyUnavailableException(exception);
        }
    }

    @Transactional
    public void deleteDentalImage(ClinicalRecordsActor actor, Long imageId) {
        DentalImage dentalImage = findManageableImage(actor, imageId);
        try {
            gridFSBucket.delete(parseStoredObjectId(dentalImage.getGridfsFileId()));
            if (dentalImage.getThumbnailGridfsId() != null) {
                thumbnailGridFSBucket.delete(parseStoredObjectId(dentalImage.getThumbnailGridfsId()));
            }
        } catch (RuntimeException exception) {
            log.warn("Clinical image {} storage deletion failed", dentalImage.getId());
            throw new ClinicalDependencyUnavailableException(exception);
        }

        dentalImageRepository.delete(dentalImage);
        log.info("Deleted dental image {}", imageId);
    }

    @Transactional
    public DentalImageResponse updateImageMetadata(
            ClinicalRecordsActor actor,
            Long imageId,
            String description,
            String tags,
            String toothNumber,
            Boolean isPrimary) {
        DentalImage dentalImage = findManageableImage(actor, imageId);
        if (description != null) {
            dentalImage.setDescription(description);
        }
        if (tags != null) {
            dentalImage.setTags(tags);
        }
        if (toothNumber != null) {
            dentalImage.setToothNumber(toothNumber);
        }
        if (isPrimary != null) {
            dentalImage.setIsPrimary(isPrimary);
        }

        DentalImage saved = dentalImageRepository.save(dentalImage);
        log.info("Updated dental image {} metadata", imageId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DentalImageResponse> getPatientImages(ClinicalRecordsActor actor, Long patientId) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        List<DentalImage> images;
        if (actor.isSystemAdmin() || ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            images = dentalImageRepository.findByPatientIdOrderByCreatedAtDesc(targetPatientId);
        } else if (actor.isDentist()) {
            images = dentalImageRepository.findByPatientIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
                    targetPatientId,
                    actor.userId(),
                    actor.requiredClinicId());
        } else {
            throw new AccessDeniedException("Clinical image read access is unavailable");
        }
        return images.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<DentalImageResponse> getClinicalNoteImages(
            ClinicalRecordsActor actor,
            Long clinicalNoteId) {
        long targetClinicalNoteId = ClinicalRecordsAccess.requirePositive(clinicalNoteId);
        List<DentalImage> images;
        if (actor.isSystemAdmin()) {
            images = dentalImageRepository.findByClinicalNoteIdOrderByCreatedAtDesc(targetClinicalNoteId);
        } else if (actor.isPatient()) {
            images = dentalImageRepository.findByClinicalNoteIdAndPatientIdOrderByCreatedAtDesc(
                    targetClinicalNoteId,
                    actor.userId());
        } else if (actor.isDentist()) {
            images = dentalImageRepository.findByClinicalNoteIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
                    targetClinicalNoteId,
                    actor.userId(),
                    actor.requiredClinicId());
        } else {
            throw new AccessDeniedException("Clinical image read access is unavailable");
        }
        return images.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<DentalImageResponse> getVisitImages(ClinicalRecordsActor actor, Long visitId) {
        long targetVisitId = ClinicalRecordsAccess.requirePositive(visitId);
        List<DentalImage> images;
        if (actor.isSystemAdmin()) {
            images = dentalImageRepository.findByVisitIdOrderByCreatedAtDesc(targetVisitId);
        } else if (actor.isPatient()) {
            images = dentalImageRepository.findByVisitIdAndPatientIdOrderByCreatedAtDesc(targetVisitId, actor.userId());
        } else if (actor.isDentist()) {
            images = dentalImageRepository.findByVisitIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
                    targetVisitId,
                    actor.userId(),
                    actor.requiredClinicId());
        } else {
            throw new AccessDeniedException("Clinical image read access is unavailable");
        }
        return images.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<DentalImageResponse> getPatientImagesByType(
            ClinicalRecordsActor actor,
            Long patientId,
            String imageType) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        requireNonBlank(imageType);
        List<DentalImage> images;
        if (actor.isSystemAdmin() || ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            images = dentalImageRepository.findByPatientIdAndImageTypeOrderByCreatedAtDesc(targetPatientId, imageType);
        } else if (actor.isDentist()) {
            images = dentalImageRepository.findByPatientIdAndDentistIdAndClinicIdAndImageTypeOrderByCreatedAtDesc(
                    targetPatientId,
                    actor.userId(),
                    actor.requiredClinicId(),
                    imageType);
        } else {
            throw new AccessDeniedException("Clinical image read access is unavailable");
        }
        return images.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<DentalImageResponse> getPatientImagesByTooth(
            ClinicalRecordsActor actor,
            Long patientId,
            String toothNumber) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        requireNonBlank(toothNumber);
        List<DentalImage> images;
        if (actor.isSystemAdmin() || ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            images = dentalImageRepository.findByPatientIdAndToothNumber(targetPatientId, toothNumber);
        } else if (actor.isDentist()) {
            images = dentalImageRepository.findByPatientIdAndDentistIdAndClinicIdAndToothNumber(
                    targetPatientId,
                    actor.userId(),
                    actor.requiredClinicId(),
                    toothNumber);
        } else {
            throw new AccessDeniedException("Clinical image read access is unavailable");
        }
        return images.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<DentalImageResponse> searchPatientImagesByTag(
            ClinicalRecordsActor actor,
            Long patientId,
            String tag) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        requireNonBlank(tag);
        List<DentalImage> images;
        if (actor.isSystemAdmin() || ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            images = dentalImageRepository.findByPatientIdAndTag(targetPatientId, tag);
        } else if (actor.isDentist()) {
            images = dentalImageRepository.findByPatientIdAndDentistIdAndClinicIdAndTag(
                    targetPatientId,
                    actor.userId(),
                    actor.requiredClinicId(),
                    tag);
        } else {
            throw new AccessDeniedException("Clinical image read access is unavailable");
        }
        return images.stream().map(this::toResponse).toList();
    }

    private ObjectId uploadOriginal(
            MultipartFile file,
            ClinicalRecordsAccess.WriteOwner owner,
            String imageType,
            String originalFilename) {
        Document metadata = new Document()
                .append("patientId", owner.patientId())
                .append("dentistId", owner.dentistId())
                .append("clinicId", owner.clinicId())
                .append("imageType", imageType)
                .append("contentType", file.getContentType());
        try (InputStream inputStream = file.getInputStream()) {
            return gridFSBucket.uploadFromStream(
                    originalFilename,
                    inputStream,
                    new GridFSUploadOptions().metadata(metadata));
        } catch (IOException | RuntimeException exception) {
            log.warn("Clinical image original upload failed");
            throw new ClinicalDependencyUnavailableException(exception);
        }
    }

    private String uploadThumbnail(
            MultipartFile file,
            ClinicalRecordsAccess.WriteOwner owner,
            ObjectId originalFileId,
            String originalFilename) {
        try {
            byte[] thumbnailBytes = generateThumbnail(file);
            Document metadata = new Document()
                    .append("originalFileId", originalFileId.toString())
                    .append("patientId", owner.patientId())
                    .append("dentistId", owner.dentistId())
                    .append("clinicId", owner.clinicId())
                    .append("imageType", "THUMBNAIL");
            ObjectId thumbnailId = thumbnailGridFSBucket.uploadFromStream(
                    "thumb_" + originalFilename,
                    new ByteArrayInputStream(thumbnailBytes),
                    new GridFSUploadOptions().metadata(metadata));
            return thumbnailId.toString();
        } catch (IOException | RuntimeException exception) {
            log.warn("Clinical image thumbnail generation or upload failed");
            return null;
        }
    }

    private DentalImage findReadableImage(ClinicalRecordsActor actor, Long imageId) {
        long targetImageId = ClinicalRecordsAccess.requirePositive(imageId);
        Optional<DentalImage> image;
        if (actor.isSystemAdmin()) {
            image = dentalImageRepository.findById(targetImageId);
        } else {
            image = Optional.empty();
            if (actor.isPatient()) {
                image = dentalImageRepository.findByIdAndPatientId(targetImageId, actor.userId());
            }
            if (image.isEmpty() && actor.isDentist()) {
                image = dentalImageRepository.findByIdAndDentistIdAndClinicId(
                        targetImageId,
                        actor.userId(),
                        actor.requiredClinicId());
            }
            if (!actor.isPatient() && !actor.isDentist()) {
                throw new AccessDeniedException("Clinical image read access is unavailable");
            }
        }
        return image.orElseThrow(ClinicalResourceNotFoundException::new);
    }

    private DentalImage findManageableImage(ClinicalRecordsActor actor, Long imageId) {
        long targetImageId = ClinicalRecordsAccess.requirePositive(imageId);
        ClinicalRecordsAccess.requireClinicalManager(actor);
        Optional<DentalImage> image = actor.isSystemAdmin()
                ? dentalImageRepository.findById(targetImageId)
                : dentalImageRepository.findByIdAndDentistIdAndClinicId(
                        targetImageId,
                        actor.userId(),
                        actor.requiredClinicId());
        return image.orElseThrow(ClinicalResourceNotFoundException::new);
    }

    private void validateLinks(
            ClinicalRecordsActor actor,
            ClinicalRecordsAccess.WriteOwner owner,
            Long clinicalNoteId,
            Long visitId) {
        if (clinicalNoteId != null) {
            ClinicalNote note = findManageableClinicalNote(actor, clinicalNoteId);
            requireMatchingOwner(note, owner);
        }
        if (visitId != null) {
            ServiceVisit visit = findManageableVisit(actor, visitId);
            requireMatchingOwner(visit, owner);
        }
    }

    private ClinicalNote findManageableClinicalNote(ClinicalRecordsActor actor, Long noteId) {
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

    private void validateImageFile(MultipartFile file, String imageType) {
        if (file == null || file.isEmpty() || file.getSize() > fileUploadConfig.getMaxFileSize()) {
            throw new InvalidClinicalRequestException();
        }
        requireNonBlank(imageType);
        String contentType = file.getContentType();
        if (contentType == null || !Arrays.asList(fileUploadConfig.getAllowedImageTypes()).contains(contentType)) {
            throw new InvalidClinicalRequestException();
        }
    }

    private void requireNonBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidClinicalRequestException();
        }
    }

    private String safeOriginalFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        return filename == null || filename.isBlank() ? "dental-image" : filename;
    }

    private ObjectId parseStoredObjectId(String objectId) {
        try {
            return new ObjectId(objectId);
        } catch (IllegalArgumentException exception) {
            throw new ClinicalDependencyUnavailableException(exception);
        }
    }

    private byte[] generateThumbnail(MultipartFile file) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Thumbnails.of(file.getInputStream())
                    .size(fileUploadConfig.getThumbnailWidth(), fileUploadConfig.getThumbnailHeight())
                    .outputFormat("jpg")
                    .outputQuality(0.8)
                    .toOutputStream(outputStream);
            return outputStream.toByteArray();
        }
    }

    private DentalImageResponse toResponse(DentalImage dentalImage) {
        DentalImageResponse response = DentalImageResponse.builder()
                .id(dentalImage.getId())
                .patientId(dentalImage.getPatientId())
                .dentistId(dentalImage.getDentistId())
                .clinicId(dentalImage.getClinicId())
                .clinicalNoteId(dentalImage.getClinicalNoteId())
                .visitId(dentalImage.getVisitId())
                .originalFilename(dentalImage.getOriginalFilename())
                .contentType(dentalImage.getContentType())
                .fileSize(dentalImage.getFileSize())
                .imageType(dentalImage.getImageType())
                .toothNumber(dentalImage.getToothNumber())
                .description(dentalImage.getDescription())
                .tags(dentalImage.getTags())
                .isPrimary(dentalImage.getIsPrimary())
                .createdAt(dentalImage.getCreatedAt())
                .updatedAt(dentalImage.getUpdatedAt())
                .downloadUrl("/clinical-records/image/" + dentalImage.getId() + "/download")
                .thumbnailUrl(dentalImage.getThumbnailGridfsId() == null
                        ? null
                        : "/clinical-records/image/" + dentalImage.getId() + "/thumbnail")
                .build();

        try {
            response.setPatientName(authServiceClient.getUserFullName(dentalImage.getPatientId()));
        } catch (Exception exception) {
            log.warn("Failed to enrich dental image {} from a dependent service", dentalImage.getId());
        }
        try {
            response.setDentistName(authServiceClient.getUserFullName(dentalImage.getDentistId()));
        } catch (Exception exception) {
            log.warn("Failed to enrich dental image {} from a dependent service", dentalImage.getId());
        }
        try {
            response.setClinicName(clinicServiceClient.getClinic(dentalImage.getClinicId()).getName());
        } catch (Exception exception) {
            log.warn("Failed to enrich dental image {} from a dependent service", dentalImage.getId());
        }
        return response;
    }
}
