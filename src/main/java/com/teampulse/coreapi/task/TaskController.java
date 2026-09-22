package com.teampulse.coreapi.task;

import com.teampulse.coreapi.common.NotFoundException;
import com.teampulse.coreapi.project.Project;
import com.teampulse.coreapi.project.ProjectRepository;
import com.teampulse.coreapi.user.User;
import com.teampulse.coreapi.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public TaskController(TaskRepository taskRepository, ProjectRepository projectRepository,
                           UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@Valid @RequestBody TaskRequest request) {
        Project project = findProjectOrThrow(request.projectId());
        User assignee = resolveAssignee(request.assigneeId());
        Task task = new Task(project, request.title(), request.status(), assignee);
        return TaskResponse.from(taskRepository.save(task));
    }

    @GetMapping
    public List<TaskResponse> list() {
        return taskRepository.findAll().stream().map(TaskResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable UUID id) {
        return TaskResponse.from(findOrThrow(id));
    }

    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable UUID id, @Valid @RequestBody TaskRequest request) {
        Task task = findOrThrow(id);
        task.setTitle(request.title());
        task.setStatus(request.status());
        task.setAssignee(resolveAssignee(request.assigneeId()));
        return TaskResponse.from(taskRepository.save(task));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        Task task = findOrThrow(id);
        taskRepository.delete(task);
    }

    private Task findOrThrow(UUID id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Task " + id + " not found"));
    }

    private Project findProjectOrThrow(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
    }

    private User resolveAssignee(UUID assigneeId) {
        if (assigneeId == null) {
            return null;
        }
        return userRepository.findById(assigneeId)
                .orElseThrow(() -> new NotFoundException("User " + assigneeId + " not found"));
    }
}
