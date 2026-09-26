package com.teampulse.coreapi.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name="tasks")
@Setter
@Getter
public class Task{
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID taskId;
    private String title;
    private String description;
    private String notes;
    private LocalDate startDate;
    private LocalDate endDate;
    @Enumerated(EnumType.STRING)
    private Status status;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name ="project_id", nullable = false)
    private Project project;

    public Task() {}

    public Task(String title, Project project) {
        this.title = Objects.requireNonNull(title);
        this.project = Objects.requireNonNull(project);
        this.status=Status.NOT_STARTED;
    }

}