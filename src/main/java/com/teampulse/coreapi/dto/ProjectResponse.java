package com.teampulse.coreapi.dto;

import com.teampulse.coreapi.entity.Project;

import java.util.UUID;

/**
 * Outbound view of a project.
 *
 * Deliberately does not carry its tasks: touching project.getTasks() here would
 * initialise the lazy collection once per project and turn any list endpoint into
 * an N+1. Tasks are fetched through their own endpoint instead.
 */
public record ProjectResponse(UUID projectId, String projectName) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getProjectId(), project.getProjectName());
    }
}
