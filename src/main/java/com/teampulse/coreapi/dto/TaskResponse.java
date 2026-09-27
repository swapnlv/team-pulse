package com.teampulse.coreapi.dto;

import com.teampulse.coreapi.entity.Status;
import com.teampulse.coreapi.entity.Task;

import java.time.LocalDate;
import java.util.UUID;

/** Outbound view of a task, carrying its project as an id rather than a nested object. */
public record TaskResponse(
        UUID taskId,
        UUID projectId,
        String title,
        String description,
        String notes,
        LocalDate startDate,
        LocalDate endDate,
        Status status
) {
    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getTaskId(),
                // project is LAZY, but reading only its identifier is served from the
                // proxy and does not trigger a select.
                task.getProject().getProjectId(),
                task.getTitle(),
                task.getDescription(),
                task.getNotes(),
                task.getStartDate(),
                task.getEndDate(),
                task.getStatus()
        );
    }
}
