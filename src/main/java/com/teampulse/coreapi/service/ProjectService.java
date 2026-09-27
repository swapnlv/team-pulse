package com.teampulse.coreapi.service;

import com.teampulse.coreapi.dto.ProjectRequest;
import com.teampulse.coreapi.dto.ProjectResponse;
import com.teampulse.coreapi.entity.Project;
import com.teampulse.coreapi.exception.ResourceNotFoundException;
import com.teampulse.coreapi.repository.ProjectRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Project use cases. Takes and returns DTOs only, so entities never reach the web layer
 * and the transaction that loaded them is always still open while they're read.
 */
@Service
@Transactional
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public ProjectResponse create(ProjectRequest request) {
        Project project = new Project();
        project.setProjectName(request.projectName());
        return ProjectResponse.from(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID projectId) {
        return ProjectResponse.from(require(projectId));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return projectRepository.findAll().stream().map(ProjectResponse::from).toList();
    }

    public ProjectResponse update(UUID projectId, ProjectRequest request) {
        Project project = require(projectId);
        project.setProjectName(request.projectName());
        // No save() call: project is a managed entity, so Hibernate's dirty checking
        // writes the change when this transaction commits.
        return ProjectResponse.from(project);
    }

    public void delete(UUID projectId) {
        projectRepository.delete(require(projectId));
    }

    private Project require(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }
}
