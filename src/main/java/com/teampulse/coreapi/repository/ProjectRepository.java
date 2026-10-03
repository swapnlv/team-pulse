package com.teampulse.coreapi.repository;

import com.teampulse.coreapi.entity.Project;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * ListCrudRepository rather than CrudRepository so findAll() hands back a List instead of
 * an Iterable, which is what the service layer wants to stream over.
 *
 * findAll() is left as the plain inherited lazy query on purpose. ProjectResponse carries no
 * tasks, so no endpoint reads project.getTasks() — a fetch join here would load every task
 * row only to discard it. The cost of that laziness is measured, not assumed, by
 * ProjectTaskPersistenceTest#loadingEveryProjectsTasksLazilyCostsOnePlusNStatements.
 */
@Repository
public interface ProjectRepository extends ListCrudRepository<Project, UUID> {
}
