package press.mizhifei.dentist.clinicalrecords.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import press.mizhifei.dentist.clinicalrecords.dto.ApiResponse;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ClinicalResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleClinicalResourceNotFound(
            ClinicalResourceNotFoundException exception) {
        log.warn("Clinical resource was not found or was outside the caller scope");
        return error(HttpStatus.NOT_FOUND, "Clinical resource not found");
    }

    @ExceptionHandler(InvalidClinicalRequestException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalidClinicalRequest(
            InvalidClinicalRequestException exception) {
        log.warn("Clinical request failed validation");
        return error(HttpStatus.BAD_REQUEST, "Invalid clinical request");
    }

    @ExceptionHandler(ClinicalStateConflictException.class)
    public ResponseEntity<ApiResponse<Object>> handleClinicalStateConflict(
            ClinicalStateConflictException exception) {
        log.warn("Clinical request conflicted with resource state");
        return error(HttpStatus.CONFLICT, "Clinical resource state conflict");
    }

    @ExceptionHandler(ClinicalDependencyUnavailableException.class)
    public ResponseEntity<ApiResponse<Object>> handleClinicalDependencyUnavailable(
            ClinicalDependencyUnavailableException exception) {
        log.warn("Clinical storage dependency is unavailable");
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Clinical service is temporarily unavailable");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDenied(AccessDeniedException exception) {
        log.warn("Clinical access was denied");
        return error(HttpStatus.FORBIDDEN, "Clinical access denied");
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MaxUploadSizeExceededException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleInvalidRequest(Exception exception) {
        log.warn("Clinical request could not be parsed or validated");
        return error(HttpStatus.BAD_REQUEST, "Invalid clinical request");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalStateException(IllegalStateException exception) {
        log.warn("Clinical request conflicted with an unexpected resource state");
        return error(HttpStatus.CONFLICT, "Clinical resource state conflict");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Object>> handleRuntimeException(RuntimeException exception) {
        log.error("Unhandled clinical records runtime exception");
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGenericException(Exception exception) {
        log.error("Unhandled clinical records exception");
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred");
    }

    private ResponseEntity<ApiResponse<Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(message));
    }
}
