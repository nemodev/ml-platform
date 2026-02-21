package com.mlplatform.config;

import com.mlplatform.dto.ErrorResponse;
import com.mlplatform.service.AirflowUnavailableException;
import com.mlplatform.service.JupyterHubUnavailableException;
import com.mlplatform.service.KServeUnavailableException;
import com.mlplatform.service.MlflowUnavailableException;
import com.mlplatform.service.NotebookStorageUnavailableException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("Unauthorized", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("Forbidden", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(JupyterHubUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleJupyterHubUnavailable(JupyterHubUnavailableException ex) {
        log.error("JupyterHub unavailable: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("ServiceUnavailable", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MlflowUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleMlflowUnavailable(MlflowUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("ServiceUnavailable", "Experiment tracking server is unavailable", Instant.now()));
    }

    @ExceptionHandler(AirflowUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAirflowUnavailable(AirflowUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("ServiceUnavailable", "Pipeline orchestration service is unavailable", Instant.now()));
    }

    @ExceptionHandler(NotebookStorageUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleNotebookStorageUnavailable(NotebookStorageUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("ServiceUnavailable", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(KServeUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleKServeUnavailable(KServeUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("ServiceUnavailable", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.getReasonPhrase(), ex.getReason(), Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("InternalServerError", ex.getMessage(), Instant.now()));
    }
}
