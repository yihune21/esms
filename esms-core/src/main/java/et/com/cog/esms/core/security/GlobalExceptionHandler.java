package et.com.cog.esms.core.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());

        ProblemDetail problem = ProblemDetail.builder()
                .type(URI.create("/errors/validation"))
                .title("Validation failed")
                .status(422)
                .detail("One or more fields failed validation")
                .errors(errors)
                .build();

        return ResponseEntity.unprocessableEntity()
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.builder()
                .type(URI.create("/errors/forbidden"))
                .title("Access denied")
                .status(403)
                .detail("Insufficient permissions for this operation")
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleUploadSize(MaxUploadSizeExceededException ex) {
        ProblemDetail problem = ProblemDetail.builder()
                .type(URI.create("/errors/upload"))
                .title("Upload too large")
                .status(413)
                .detail("File exceeds the maximum upload size of 25 MB")
                .build();

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.builder()
                .type(URI.create("/errors/bad-request"))
                .title("Bad request")
                .status(400)
                .detail(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> handleIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.builder()
                .type(URI.create("/errors/conflict"))
                .title("Conflict")
                .status(409)
                .detail(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.builder()
                .type(URI.create("/errors/conflict"))
                .title("Data conflict")
                .status(409)
                .detail("A record with that identifier already exists")
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.builder()
                .type(URI.create("/errors/internal"))
                .title("Internal server error")
                .status(500)
                .detail("An unexpected error occurred")
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProblemDetail {
        private URI type;
        private String title;
        private int status;
        private String detail;
        private String instance;
        private String workspaceId;
        private List<FieldErrorDetail> errors;
    }

    @Data
    @AllArgsConstructor
    public static class FieldErrorDetail {
        private String field;
        private String message;
    }
}
