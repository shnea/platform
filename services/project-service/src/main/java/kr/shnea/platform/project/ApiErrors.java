package kr.shnea.platform.project;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(org.springframework.web.client.RestClientException.class)
    ProblemDetail identityUnavailable(org.springframework.web.client.RestClientException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Identity provider operation failed");
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Resource already exists or violates a constraint");
    }
}
