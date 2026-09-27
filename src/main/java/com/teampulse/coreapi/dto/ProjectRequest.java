package com.teampulse.coreapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Inbound payload for creating or replacing a project. */
public record ProjectRequest(
        @NotBlank(message = "projectName must not be blank")
        @Size(max = 255, message = "projectName must be at most 255 characters")
        String projectName
) {}
