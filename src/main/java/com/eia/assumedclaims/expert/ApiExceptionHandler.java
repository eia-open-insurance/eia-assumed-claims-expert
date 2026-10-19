package com.eia.assumedclaims.expert;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Locale;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiError.from(exception, request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldIssue> issues = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldIssue(
                        error.getField(),
                        error.getCode() == null ? "INVALID" : error.getCode().toUpperCase(Locale.ROOT),
                        error.getDefaultMessage()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "EIA-RNS-7004", "VALIDATION", "Request validation failed",
                "The request body is invalid.", false, issues, request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleMissingContext(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "EIA-PLT-0201", "VALIDATION", "Tenant context required",
                "The X-EIA-Tenant header or tenantId parameter is required.", false, List.of(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        String required = exception.getRequiredType() == null ? "value" : exception.getRequiredType().getSimpleName();
        return error(HttpStatus.BAD_REQUEST, "EIA-RNS-7004", "VALIDATION", "Request validation failed",
                "Parameter '" + exception.getName() + "' must be a valid " + required + ".",
                false, List.of(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "EIA-RNS-7004", "VALIDATION", "Request validation failed",
                "The request body is missing or malformed.", false, List.of(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "EIA-RNS-7006", "CONFLICT", "Data integrity conflict",
                "The request conflicts with an existing assumed claim.", true, List.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(
            NoResourceFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "EIA-RNS-7001", "NOT_FOUND", "Not found",
                "No resource exists at the requested path.", false, List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "EIA-RNS-7004", "VALIDATION", "Method not allowed",
                "The HTTP method is not supported for this resource.", false, List.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "EIA-RNS-7004", "VALIDATION", "Unsupported media type",
                "The Content-Type is not supported; use application/json.", false, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Unhandled expert error for {}", request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "EIA-RNS-7099", "INTERNAL", "Internal error",
                "The expert service could not complete the request.", true, List.of(), request);
    }

    private static ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String category,
            String title,
            String detail,
            boolean retryable,
            List<FieldIssue> issues,
            HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(new ApiError(
                "https://errors.eia.example/" + code.toLowerCase(Locale.ROOT),
                title,
                status.value(),
                detail,
                request.getRequestURI(),
                code,
                category,
                retryable,
                issues
        ));
    }
}
