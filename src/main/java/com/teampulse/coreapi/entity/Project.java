package com.teampulse.coreapi.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


@Entity
@Table(name = "projects")
@Getter
@Setter
public class Project{

    @Id
    @GeneratedValue(strategy= GenerationType.UUID)
    private UUID projectId;
    private String name;
    @OneToMany(mappedBy = "project")
    private List<Task> tasks=new ArrayList<>();

    public Project() {}

    public Project(String name, List<Task> tasks) {
        this.name = name;
        this.tasks = tasks;
    }
}