package com.teampulse.coreapi.repository;

import com.teampulse.coreapi.entity.Project;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * ListCrudRepository rather than CrudRepository so findAll() hands back a List instead of
 * an Iterable, which is what the service layer wants to stream over.
 */
@Repository
public interface ProjectRepository extends ListCrudRepository<Project, UUID> {

    /**
     * Projects with their tasks joined in: one query instead of 1 + N.
     *
     * Guarded by ProjectTaskPersistenceTest#q3b_andWhatItCostsForThreeProjects, which
     * asserts that touching every project's tasks afterwards issues no further statements.
     */
    @Override
    @EntityGraph(attributePaths = {"tasks"})
    List<Project> findAll();
}
