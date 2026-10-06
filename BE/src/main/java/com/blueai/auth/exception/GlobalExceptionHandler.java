package com.blueai.auth.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
        ApiError body = ApiError.of(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                request,
                Map.of()
        );
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.getStatus());
        if (exception.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
            long retryAfter = exception.getRetryAfterSeconds() == null
                    ? 60
                    : Math.max(1, exception.getRetryAfterSeconds());
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        }
        return response.body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed.",
                request,
                fieldErrors
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_JSON",
                "Request body is missing or malformed.",
                request,
                Map.of()
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException exception, HttpServletRequest request) {
        ApiError body = ApiError.of(
                HttpStatus.CONFLICT,
                "RESOURCE_CONFLICT",
                "The requested resource already exists.",
                request,
                Map.of()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
