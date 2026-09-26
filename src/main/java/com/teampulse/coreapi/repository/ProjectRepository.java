package com.teampulse.coreapi.repository;

import java.util.List;

import com.teampulse.coreapi.entity.Project;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;

@Repository
public interface ProjectRepository extends CrudRepository<Project, UUID> {

    @EntityGraph(attributePaths = {"tasks"})
    public List<Project> findAll();

}
