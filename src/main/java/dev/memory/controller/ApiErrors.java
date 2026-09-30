package dev.memory.controller;

import dev.memory.service.MemoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Comparator;
import java.util.stream.Stream;

@RestControllerAdvice
public class ApiErrors {
    public record FieldViolation(String field, String message) {}

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(MemoryException.class)
    ProblemDetail memory(MemoryException exception) {
        var status = switch (exception.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INVALID_PROVIDER_OUTPUT -> HttpStatus.BAD_GATEWAY;
            case PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidFields(MethodArgumentNotValidException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields.");
        var fieldViolations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        var globalViolations = exception.getBindingResult().getGlobalErrors().stream()
                .map(error -> new FieldViolation(error.getObjectName(), error.getDefaultMessage()))
                .toList();
        var violations = Stream.concat(fieldViolations.stream(), globalViolations.stream())
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        problem.setProperty("errors", violations);
        return problem;
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail invalid(Exception ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields or JSON.");
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Memory conflicts with an existing record or database constraint.");
    }
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException exception) {
        log.warn("event=database_failure", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Database operation unavailable.");
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail missingResource(NoResourceFoundException ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found.");
    }
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        // Exception messages can contain provider payloads or SQL parameters.
        log.error("event=unexpected_failure exceptionType={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error.");
    }
}
