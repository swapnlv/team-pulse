package com.teampulse.coreapi.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.teampulse.coreapi.entity.Task;

@Repository
public interface TaskRepository extends CrudRepository<Task, UUID>
{
    List<Task> findByProject_ProjectId(UUID projectId);

}
