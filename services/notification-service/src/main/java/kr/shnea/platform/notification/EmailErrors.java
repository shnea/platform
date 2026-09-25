package kr.shnea.platform.notification;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class EmailErrors {
    // Never echo rejected values (including action tokens) or provider bodies in logs/responses.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid email request")); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> rejected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", "Email request rejected"));
    }
    @ExceptionHandler(RestClientException.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("error", "Email context unavailable")); }
}
