package com.teampulse.coreapi.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TaskRequest(
        @NotNull UUID projectId,
        @NotBlank String title,
        @NotNull TaskStatus status,
        UUID assigneeId
) {
}
