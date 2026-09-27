package com.teampulse.coreapi.dto;

import com.teampulse.coreapi.entity.Status;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Inbound payload for creating or replacing a task. The project is never in the body:
 * it comes from the path on create, and a task cannot be moved between projects.
 *
 * status is required rather than optional so that PUT is an honest full replacement.
 * If it were optional, a PUT that omitted it would either silently reset a running
 * task to NOT_STARTED or silently keep the old value, and neither is guessable by a
 * caller reading the endpoint.
 */
public record TaskRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        String description,

        String notes,

        LocalDate startDate,

        LocalDate endDate,

        @NotNull(message = "status is required and must be one of NOT_STARTED, IN_PROGRESS, COMPLETED")
        Status status
) {
    @AssertTrue(message = "endDate must not be before startDate")
    public boolean isEndDateNotBeforeStartDate() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
