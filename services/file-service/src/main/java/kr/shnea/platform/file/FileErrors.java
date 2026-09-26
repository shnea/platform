package kr.shnea.platform.file;

import jakarta.servlet.http.HttpServletRequest;
import kr.shnea.platform.http.HttpProblems;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
class FileErrors {
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> failure(Exception error, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        // A disconnected download cannot be replaced with JSON after its binary headers/body were sent.
        if (response.isCommitted()) return null;
        FileFailure failure;
        if (error instanceof FileFailure known) failure = known;
        else if (error instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || error instanceof org.springframework.beans.TypeMismatchException) failure = FileFailure.invalid();
        else if (error instanceof ErrorResponse http && http.getStatusCode().value() < 500) {
            int status = http.getStatusCode().value();
            failure = new FileFailure(switch (status) {
                case 404 -> "FILE_NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "INVALID_REQUEST";
            }, status, "요청 주소·방식과 입력 형식을 확인해 주세요.");
        } else failure = FileFailure.unavailable();
        response.reset();
        response.setHeader("X-Content-Type-Options", "nosniff");
        return new ResponseEntity<>(HttpProblems.body(failure.code, failure.status, failure.getMessage(), request),
            HttpProblems.headers(request), failure.status);
    }
}
