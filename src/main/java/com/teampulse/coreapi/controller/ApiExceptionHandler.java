package com.teampulse.coreapi.controller;

import com.teampulse.coreapi.dto.ApiError;
import com.teampulse.coreapi.exception.ResourceNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/** Maps every failure the API can produce onto the single {@link ApiError} shape. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return ApiError.of(404, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    /** Bean Validation on an @Valid request body: one entry per rejected field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldProblem> problems = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiError.FieldProblem(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return ApiError.of(400, "Bad Request", "Request validation failed", request.getRequestURI(), problems);
    }

    /** Unparseable JSON, or a status string that isn't one of the enum constants. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ApiError.of(400, "Bad Request", "Malformed request body", request.getRequestURI());
    }

    /** A path variable that isn't a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return ApiError.of(400, "Bad Request",
                "'" + ex.getValue() + "' is not a valid value for " + ex.getName(),
                request.getRequestURI());
    }

    /**
     * Currently reached by deleting a project that still has tasks: the tasks.project_id
     * foreign key refuses it. Left to the database rather than pre-checked in the service,
     * because a pre-check is a race — a task can be inserted between the check and the delete.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleConflict(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Constraint violation on {}", request.getRequestURI(), ex);
        return ApiError.of(409, "Conflict",
                "The request conflicts with the current state of the data", request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleUnexpected(Exception ex, HttpServletRequest request) {
        // Log the cause, but never leak it to the caller.
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return ApiError.of(500, "Internal Server Error", "Unexpected error", request.getRequestURI());
    }
}
