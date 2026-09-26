package com.teampulse.coreapi.repository;

import com.teampulse.coreapi.entity.Project;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProjectRepository extends CrudRepository<Project, UUID> {


}
