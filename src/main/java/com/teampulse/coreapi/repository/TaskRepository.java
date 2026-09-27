package com.teampulse.coreapi.repository;

import com.teampulse.coreapi.entity.Task;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends ListCrudRepository<Task, UUID> {

    /**
     * Tasks of one project, newest derived-query form.
     *
     * The underscore is load-bearing. findByProjectId would fail to resolve: Spring Data
     * walks to the Project association and then looks for a property literally named "id",
     * which this entity calls projectId. The underscore states the split explicitly —
     * property "project", then property "projectId" on it.
     */
    List<Task> findByProject_ProjectId(UUID projectId);
}
