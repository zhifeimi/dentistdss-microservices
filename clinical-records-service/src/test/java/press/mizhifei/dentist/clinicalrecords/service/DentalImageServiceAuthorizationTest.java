package press.mizhifei.dentist.clinicalrecords.service;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import press.mizhifei.dentist.clinicalrecords.client.AuthServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.ClinicServiceClient;
import press.mizhifei.dentist.clinicalrecords.config.FileUploadConfig;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalResourceNotFoundException;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;
import press.mizhifei.dentist.clinicalrecords.model.ClinicalNote;
import press.mizhifei.dentist.clinicalrecords.model.DentalImage;
import press.mizhifei.dentist.clinicalrecords.repository.ClinicalNoteRepository;
import press.mizhifei.dentist.clinicalrecords.repository.DentalImageRepository;
import press.mizhifei.dentist.clinicalrecords.repository.ServiceVisitRepository;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;

import java.io.InputStream;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DentalImageServiceAuthorizationTest {

    private DentalImageRepository dentalImageRepository;
    private ClinicalNoteRepository clinicalNoteRepository;
    private ServiceVisitRepository serviceVisitRepository;
    private GridFSBucket gridFSBucket;
    private GridFSBucket thumbnailGridFSBucket;
    private DentalImageService service;

    @BeforeEach
    void setUp() {
        dentalImageRepository = mock(DentalImageRepository.class);
        clinicalNoteRepository = mock(ClinicalNoteRepository.class);
        serviceVisitRepository = mock(ServiceVisitRepository.class);
        gridFSBucket = mock(GridFSBucket.class);
        thumbnailGridFSBucket = mock(GridFSBucket.class);
        service = new DentalImageService(
                dentalImageRepository,
                clinicalNoteRepository,
                serviceVisitRepository,
                mock(AuthServiceClient.class),
                mock(ClinicServiceClient.class),
                gridFSBucket,
                thumbnailGridFSBucket,
                new FileUploadConfig());
    }

    @Test
    void deniedOriginalDownloadDoesNotOpenGridFsStream() {
        when(dentalImageRepository.findByIdAndPatientId(100L, 42L)).thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.downloadDentalImage(patientActor(), 100L));

        verify(dentalImageRepository).findByIdAndPatientId(100L, 42L);
        verifyNoInteractions(gridFSBucket, thumbnailGridFSBucket);
    }

    @Test
    void deniedThumbnailDownloadDoesNotOpenThumbnailGridFsStream() {
        when(dentalImageRepository.findByIdAndPatientId(100L, 42L)).thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.downloadThumbnail(patientActor(), 100L));

        verify(dentalImageRepository).findByIdAndPatientId(100L, 42L);
        verifyNoInteractions(gridFSBucket, thumbnailGridFSBucket);
    }

    @Test
    void dentistOutsideImageScopeCannotDeleteAnyBlob() {
        when(dentalImageRepository.findByIdAndDentistIdAndClinicId(100L, 84L, 7L))
                .thenReturn(Optional.empty());

        assertThrows(
                ClinicalResourceNotFoundException.class,
                () -> service.deleteDentalImage(dentistActor(), 100L));

        verify(dentalImageRepository).findByIdAndDentistIdAndClinicId(100L, 84L, 7L);
        verify(dentalImageRepository, never()).delete(any(DentalImage.class));
        verifyNoInteractions(gridFSBucket, thumbnailGridFSBucket);
    }

    @Test
    void authorizedDownloadUsesObjectIdFromScopedRelationalRecord() {
        ObjectId originalFileId = new ObjectId();
        DentalImage image = DentalImage.builder()
                .id(100L)
                .patientId(42L)
                .dentistId(84L)
                .clinicId(7L)
                .gridfsFileId(originalFileId.toHexString())
                .build();
        GridFSDownloadStream downloadStream = mock(GridFSDownloadStream.class);
        when(dentalImageRepository.findByIdAndDentistIdAndClinicId(100L, 84L, 7L))
                .thenReturn(Optional.of(image));
        when(gridFSBucket.openDownloadStream(originalFileId)).thenReturn(downloadStream);

        InputStream result = service.downloadDentalImage(dentistActor(), 100L);

        assertSame(downloadStream, result);
        verify(gridFSBucket).openDownloadStream(originalFileId);
        verifyNoInteractions(thumbnailGridFSBucket);
    }

    @Test
    void mismatchedLinkedNotePreventsAllBlobUploads() {
        ClinicalNote linkedNote = ClinicalNote.builder()
                .id(25L)
                .patientId(999L)
                .dentistId(84L)
                .clinicId(7L)
                .build();
        when(clinicalNoteRepository.findByIdAndDentistIdAndClinicId(25L, 84L, 7L))
                .thenReturn(Optional.of(linkedNote));

        assertThrows(
                InvalidClinicalRequestException.class,
                () -> service.uploadDentalImage(
                        dentistActor(),
                        mock(org.springframework.web.multipart.MultipartFile.class),
                        42L,
                        null,
                        null,
                        25L,
                        null,
                        "X_RAY",
                        null,
                        null,
                        null));

        verify(clinicalNoteRepository).findByIdAndDentistIdAndClinicId(25L, 84L, 7L);
        verifyNoInteractions(dentalImageRepository, serviceVisitRepository, gridFSBucket, thumbnailGridFSBucket);
    }

    private ClinicalRecordsActor patientActor() {
        return new ClinicalRecordsActor(42L, Set.of("PATIENT"), null);
    }

    private ClinicalRecordsActor dentistActor() {
        return new ClinicalRecordsActor(84L, Set.of("DENTIST"), 7L);
    }
}
