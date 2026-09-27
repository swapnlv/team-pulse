package com.teampulse.coreapi.service;

import com.teampulse.coreapi.dto.TaskRequest;
import com.teampulse.coreapi.dto.TaskResponse;
import com.teampulse.coreapi.entity.Project;
import com.teampulse.coreapi.entity.Task;
import com.teampulse.coreapi.exception.ResourceNotFoundException;
import com.teampulse.coreapi.repository.ProjectRepository;
import com.teampulse.coreapi.repository.TaskRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Task use cases. Every task lives under a project, so each method that names a project
 * verifies it exists first and 404s on the project rather than returning an empty list
 * or a confusing 500.
 */
@Service
@Transactional
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;

    public TaskService(TaskRepository taskRepository, ProjectRepository projectRepository) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
    }

    public TaskResponse createForProject(UUID projectId, TaskRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        Task task = new Task(request.title(), project);
        apply(request, task);
        return TaskResponse.from(taskRepository.save(task));
    }

    @Transactional(readOnly = true)
    public TaskResponse get(UUID taskId) {
        return TaskResponse.from(require(taskId));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listByProject(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        return taskRepository.findByProject_ProjectId(projectId).stream()
                .map(TaskResponse::from)
                .toList();
    }

    /** Full replacement, including status. The task's project is not changeable. */
    public TaskResponse update(UUID taskId, TaskRequest request) {
        Task task = require(taskId);
        task.setTitle(request.title());
        apply(request, task);
        return TaskResponse.from(task);
    }

    public void delete(UUID taskId) {
        taskRepository.delete(require(taskId));
    }

    private void apply(TaskRequest request, Task task) {
        task.setDescription(request.description());
        task.setNotes(request.notes());
        task.setStartDate(request.startDate());
        task.setEndDate(request.endDate());
        task.setStatus(request.status());
    }

    private Task require(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
    }
}
