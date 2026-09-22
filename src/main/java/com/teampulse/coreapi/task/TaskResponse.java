package com.teampulse.coreapi.task;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID projectId,
        String title,
        TaskStatus status,
        UUID assigneeId,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getProject().getId(),
                task.getTitle(),
                task.getStatus(),
                task.getAssignee() == null ? null : task.getAssignee().getId(),
                task.getVersion(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
