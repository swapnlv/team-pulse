package com.teampulse.coreapi.controller;

import com.teampulse.coreapi.dto.TaskRequest;
import com.teampulse.coreapi.dto.TaskResponse;
import com.teampulse.coreapi.service.TaskService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Tasks are nested under a project where the project is part of the request (create, list)
 * and addressed flatly by their own id once they exist, since a task id is globally unique
 * and repeating the project in the path would let the two disagree.
 */
@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/projects/{projectId}/tasks")
    public ResponseEntity<TaskResponse> create(@PathVariable UUID projectId,
                                               @Valid @RequestBody TaskRequest request) {
        TaskResponse created = taskService.createForProject(projectId, request);
        return ResponseEntity
                .created(URI.create("/api/tasks/" + created.taskId()))
                .body(created);
    }

    @GetMapping("/projects/{projectId}/tasks")
    public List<TaskResponse> listByProject(@PathVariable UUID projectId) {
        return taskService.listByProject(projectId);
    }

    @GetMapping("/tasks/{taskId}")
    public TaskResponse get(@PathVariable UUID taskId) {
        return taskService.get(taskId);
    }

    @PutMapping("/tasks/{taskId}")
    public TaskResponse update(@PathVariable UUID taskId, @Valid @RequestBody TaskRequest request) {
        return taskService.update(taskId, request);
    }

    @DeleteMapping("/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID taskId) {
        taskService.delete(taskId);
    }
}
