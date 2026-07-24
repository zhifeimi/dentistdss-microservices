package press.mizhifei.dentist.clinicalrecords.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import press.mizhifei.dentist.clinicalrecords.dto.ApiResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void storageFailureResponseDoesNotExposeDependencyDetails() {
        ResponseEntity<ApiResponse<Object>> response = handler.handleClinicalDependencyUnavailable(
                new ClinicalDependencyUnavailableException(
                        new IllegalStateException("GridFS connection password=secret")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Clinical service is temporarily unavailable", response.getBody().getMessage());
    }

    @Test
    void unexpectedFailureResponseDoesNotExposeClinicalDetails() {
        ResponseEntity<ApiResponse<Object>> response = handler.handleRuntimeException(
                new RuntimeException("Patient 42 diagnosis and note contents"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("An internal error occurred", response.getBody().getMessage());
    }
}
