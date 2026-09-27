package com.teampulse.coreapi.controller;

import com.teampulse.coreapi.dto.ProjectRequest;
import com.teampulse.coreapi.dto.ProjectResponse;
import com.teampulse.coreapi.service.ProjectService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        ProjectResponse created = projectService.create(request);
        return ResponseEntity
                .created(URI.create("/api/projects/" + created.projectId()))
                .body(created);
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.list();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable UUID projectId) {
        return projectService.get(projectId);
    }

    @PutMapping("/{projectId}")
    public ProjectResponse update(@PathVariable UUID projectId, @Valid @RequestBody ProjectRequest request) {
        return projectService.update(projectId, request);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId) {
        projectService.delete(projectId);
    }
}
