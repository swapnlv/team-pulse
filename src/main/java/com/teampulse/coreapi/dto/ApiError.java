package com.teampulse.coreapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * The one and only error shape this API emits, for every failing status code.
 *
 * fieldErrors is always present and is empty for failures that aren't per-field,
 * so a client can parse one shape without branching on the status.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldProblem> fieldErrors
) {
    public record FieldProblem(String field, String message) {}

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, List.of());
    }

    public static ApiError of(int status, String error, String message, String path, List<FieldProblem> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, path, fieldErrors);
    }
}
