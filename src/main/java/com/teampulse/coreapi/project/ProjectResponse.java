package com.teampulse.coreapi.project;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectResponse(UUID id, String name, OffsetDateTime createdAt) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getCreatedAt());
    }
}
