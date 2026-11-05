package dev.translator.api;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, ConstraintViolationException.class})
    ResponseEntity<?> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "Invalid request body or parameters."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> contentType(Exception exception) {
        return ResponseEntity.status(415).body(Map.of("code", "UNSUPPORTED_MEDIA_TYPE", "message", "Use application/json."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception exception) {
        return ResponseEntity.status(405).body(Map.of("code", "METHOD_NOT_ALLOWED", "message", "HTTP method is not supported."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception exception) {
        // Do not log exception messages: provider/database exceptions can contain input or credentials.
        log.error("Unhandled application failure: {}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR", "message", "The request could not be completed."));
    }
}
