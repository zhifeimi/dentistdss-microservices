package press.mizhifei.dentist.clinicalrecords.service;

import com.mongodb.client.gridfs.GridFSBucket;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.multipart.MultipartFile;
import press.mizhifei.dentist.clinicalrecords.client.AuthServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.ClinicServiceClient;
import press.mizhifei.dentist.clinicalrecords.config.FileUploadConfig;
import press.mizhifei.dentist.clinicalrecords.image.ImageSanitizer;
import press.mizhifei.dentist.clinicalrecords.image.SanitizedImage;
import press.mizhifei.dentist.clinicalrecords.model.DentalImage;
import press.mizhifei.dentist.clinicalrecords.repository.ClinicalNoteRepository;
import press.mizhifei.dentist.clinicalrecords.repository.DentalImageRepository;
import press.mizhifei.dentist.clinicalrecords.repository.ServiceVisitRepository;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DATA-03 evidence suite for the upload transaction boundary: a persistence
 * failure after the GridFS writes compensates the blobs (no orphaned
 * storage), and deletion removes the metadata row BEFORE the blobs (an
 * orphaned blob is tolerable; broken metadata is not).
 */
class DentalImageUploadCompensationTest {

    private DentalImageRepository dentalImageRepository;
    private GridFSBucket gridFSBucket;
    private GridFSBucket thumbnailGridFSBucket;
    private ImageSanitizer imageSanitizer;
    private DentalImageService service;

    private final ObjectId originalId = new ObjectId();
    private final ObjectId thumbnailId = new ObjectId();

    @BeforeEach
    void setUp() {
        dentalImageRepository = mock(DentalImageRepository.class);
        gridFSBucket = mock(GridFSBucket.class);
        thumbnailGridFSBucket = mock(GridFSBucket.class);
        imageSanitizer = mock(ImageSanitizer.class);
        service = new DentalImageService(
                dentalImageRepository,
                mock(ClinicalNoteRepository.class),
                mock(ServiceVisitRepository.class),
                mock(AuthServiceClient.class),
                mock(ClinicServiceClient.class),
                gridFSBucket,
                thumbnailGridFSBucket,
                new FileUploadConfig(),
                imageSanitizer);
    }

    @Test
    void persistenceFailureCompensatesBothGridfsBlobs() {
        SanitizedImage sanitized = cannedSanitized(true);
        when(imageSanitizer.sanitize(any())).thenReturn(sanitized);
        when(gridFSBucket.uploadFromStream(anyString(), any(), any())).thenReturn(originalId);
        when(thumbnailGridFSBucket.uploadFromStream(anyString(), any(), any())).thenReturn(thumbnailId);
        when(dentalImageRepository.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("pg down"));

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> upload());

        verify(gridFSBucket).delete(originalId);
        verify(thumbnailGridFSBucket).delete(thumbnailId);
    }

    @Test
    void originalUploadFailureHasNothingToCompensate() {
        when(imageSanitizer.sanitize(any())).thenReturn(cannedSanitized(true));
        when(gridFSBucket.uploadFromStream(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        assertThrows(Exception.class, () -> upload());

        verify(gridFSBucket, never()).delete(any(ObjectId.class));
        verify(thumbnailGridFSBucket, never()).delete(any(ObjectId.class));
    }

    @Test
    void missingThumbnailCompensatesOnlyTheOriginal() {
        when(imageSanitizer.sanitize(any())).thenReturn(cannedSanitized(false));
        when(gridFSBucket.uploadFromStream(anyString(), any(), any())).thenReturn(originalId);
        when(dentalImageRepository.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("pg down"));

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> upload());

        verify(gridFSBucket).delete(originalId);
        verify(thumbnailGridFSBucket, never()).uploadFromStream(anyString(), any(), any());
        verify(thumbnailGridFSBucket, never()).delete(any(ObjectId.class));
    }

    @Test
    void successfulUploadStoresCanonicalMetadata() {
        SanitizedImage sanitized = cannedSanitized(true);
        when(imageSanitizer.sanitize(any())).thenReturn(sanitized);
        when(gridFSBucket.uploadFromStream(anyString(), any(), any())).thenReturn(originalId);
        when(thumbnailGridFSBucket.uploadFromStream(anyString(), any(), any())).thenReturn(thumbnailId);
        when(dentalImageRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        upload();

        InOrder order = inOrder(dentalImageRepository, gridFSBucket, thumbnailGridFSBucket);
        order.verify(gridFSBucket).uploadFromStream(anyString(), any(), any());
        order.verify(thumbnailGridFSBucket).uploadFromStream(anyString(), any(), any());
        order.verify(dentalImageRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(image ->
                "image/jpeg".equals(image.getContentType())
                        && image.getFileSize() == sanitized.canonicalBytes().length
                        && originalId.toString().equals(image.getGridfsFileId())
                        && thumbnailId.toString().equals(image.getThumbnailGridfsId())));
    }

    @Test
    void deleteRemovesMetadataRowBeforeBlobs() {
        DentalImage image = DentalImage.builder()
                .id(100L)
                .patientId(42L)
                .dentistId(84L)
                .clinicId(7L)
                .gridfsFileId(originalId.toString())
                .thumbnailGridfsId(thumbnailId.toString())
                .build();
        when(dentalImageRepository.findByIdAndDentistIdAndClinicId(100L, 84L, 7L))
                .thenReturn(Optional.of(image));

        service.deleteDentalImage(dentistActor(), 100L);

        InOrder order = inOrder(dentalImageRepository, gridFSBucket, thumbnailGridFSBucket);
        order.verify(dentalImageRepository).delete(image);
        order.verify(gridFSBucket).delete(originalId);
        order.verify(thumbnailGridFSBucket).delete(thumbnailId);
    }

    @Test
    void deleteKeepsRowDeletionWhenBlobCleanupFails() {
        DentalImage image = DentalImage.builder()
                .id(100L)
                .patientId(42L)
                .dentistId(84L)
                .clinicId(7L)
                .gridfsFileId(originalId.toString())
                .thumbnailGridfsId(thumbnailId.toString())
                .build();
        when(dentalImageRepository.findByIdAndDentistIdAndClinicId(100L, 84L, 7L))
                .thenReturn(Optional.of(image));
        org.mockito.Mockito.doThrow(new IllegalStateException("mongo down"))
                .when(gridFSBucket).delete(any(ObjectId.class));

        // Must not throw: the row deletion stands, the orphan is logged.
        service.deleteDentalImage(dentistActor(), 100L);

        verify(dentalImageRepository).delete(image);
    }

    private void upload() {
        service.uploadDentalImage(
                dentistActor(),
                mock(MultipartFile.class),
                42L,
                null,
                null,
                null,
                null,
                "X_RAY",
                null,
                null,
                null);
    }

    private SanitizedImage cannedSanitized(boolean withThumbnail) {
        return new SanitizedImage(
                new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3},
                "image/jpeg",
                withThumbnail ? new byte[] {9, 9, 9} : new byte[0],
                32,
                24);
    }

    private ClinicalRecordsActor dentistActor() {
        return new ClinicalRecordsActor(84L, Set.of("DENTIST"), 7L);
    }
}
