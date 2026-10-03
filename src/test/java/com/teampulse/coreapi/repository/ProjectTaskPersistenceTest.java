package com.teampulse.coreapi.repository;

import com.teampulse.coreapi.entity.Project;
import com.teampulse.coreapi.entity.Task;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence behaviour of Project/Task, observed rather than assumed.
 *
 * Runs against a throwaway Postgres 16 container. Flyway builds the schema from
 * V1__create_projects_and_tasks.sql and Hibernate's ddl-auto:validate then checks the
 * entity mappings against it, so a green run also means migration and entities agree.
 *
 * generate_statistics is on so the test can count the JDBC statements Hibernate actually
 * prepares, instead of reading show-sql output by eye.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProjectTaskPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired ProjectRepository projectRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired TestEntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** Detach everything, so the next read has to go to the database instead of the first-level cache. */
    private void reset() {
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
    }

    private Project saveProjectWithTwoTasks(String name) {
        Project project = projectRepository.save(new Project(name, new ArrayList<>()));
        taskRepository.save(new Task("Design the schema", project));
        taskRepository.save(new Task("Write the persistence test", project));
        return project;
    }

    // ---------------------------------------------------------------- the three questions

    @Test
    void q1_isTheTaskIdPopulatedAfterSave() {
        Project project = projectRepository.save(new Project("Apollo", new ArrayList<>()));
        statistics().clear();

        Task saved = taskRepository.save(new Task("Design the schema", project));

        System.out.println("[Q1] taskId immediately after save(), before any flush: " + saved.getTaskId());
        System.out.println("[Q1] JDBC statements prepared by that save(): " + statistics().getPrepareStatementCount());

        assertThat(saved.getTaskId()).isNotNull();
    }

    @Test
    void q2_whatIsInGetTasksOnReloadWhenOnlyTheOwningSideWasSet() {
        Project project = saveProjectWithTwoTasks("Apollo");

        // Same persistence context. Nothing ever added these tasks to the in-memory list.
        System.out.println("[Q2] before flush/clear, project.getTasks(): " + project.getTasks().size());

        reset();

        Project reloaded = projectRepository.findById(project.getProjectId()).orElseThrow();
        System.out.println("[Q2] after flush+clear, reloaded.getTasks(): " + reloaded.getTasks().size()
                + " -> " + reloaded.getTasks().stream().map(Task::getTitle).toList());
    }

    @Test
    void q3_howManyStatementsDoesLoadingOneProjectsTasksTrigger() {
        Project project = saveProjectWithTwoTasks("Apollo");
        reset();

        Project reloaded = projectRepository.findById(project.getProjectId()).orElseThrow();
        long afterFindingTheProject = statistics().getPrepareStatementCount();

        int taskCount = reloaded.getTasks().size();
        long afterTouchingTheTasks = statistics().getPrepareStatementCount();

        System.out.println("[Q3] findById(project):                     " + afterFindingTheProject + " statement(s)");
        System.out.println("[Q3] then touching getTasks() (" + taskCount + " rows):       "
                + (afterTouchingTheTasks - afterFindingTheProject) + " statement(s)");
        System.out.println("[Q3] total to see one project's tasks:      " + afterTouchingTheTasks);

        // Same information via the derived query, for comparison.
        reset();
        List<Task> viaDerivedQuery = taskRepository.findByProject_ProjectId(project.getProjectId());
        System.out.println("[Q3] findByProject_ProjectId() returned " + viaDerivedQuery.size() + " rows in "
                + statistics().getPrepareStatementCount() + " statement(s)");
    }

    /**
     * A demonstration, not a regression guard. It asserts that the N+1 *happens*.
     *
     * ProjectRepository.findAll() is the plain inherited lazy query, and this test pins what
     * that costs: 1 select for the projects, then one more per project the first time its
     * task collection is read. Three projects is four statements to fetch six rows that a
     * single join could have returned in one.
     *
     * Written this way on purpose, rather than asserting the fixed numbers a fetch join would
     * give. Nothing in the API reads project.getTasks() — ProjectResponse carries no tasks —
     * so there is no N+1 to fix today, and an @EntityGraph on findAll() would load every task
     * row only to throw it away. If someone later adds that fetch join, this test fails and
     * sends them here to change the numbers deliberately instead of shifting the query
     * profile by accident.
     */
    @Test
    void q3b_loadingEveryProjectsTasksLazilyCostsOnePlusNStatements() {
        int projectCount = 3;
        saveProjectWithTwoTasks("Apollo");
        saveProjectWithTwoTasks("Gemini");
        saveProjectWithTwoTasks("Mercury");
        reset();

        List<Project> all = projectRepository.findAll();
        long afterFindAll = statistics().getPrepareStatementCount();

        int total = all.stream().mapToInt(p -> p.getTasks().size()).sum();
        long afterTouchingEveryCollection = statistics().getPrepareStatementCount();
        long lazyLoads = afterTouchingEveryCollection - afterFindAll;

        System.out.println("[Q3b] findAll() over " + all.size() + " projects:           " + afterFindAll + " statement(s)");
        System.out.println("[Q3b] then every getTasks() (" + total + " rows total): " + lazyLoads + " statement(s)");
        System.out.println("[Q3b] total:                                " + afterTouchingEveryCollection);

        assertThat(all).hasSize(projectCount);
        assertThat(total).isEqualTo(projectCount * 2);

        // The 1: a single select for the projects themselves.
        assertThat(afterFindAll).isEqualTo(1);
        // The N: one further select per project, issued when its collection is first touched.
        assertThat(lazyLoads).isEqualTo(projectCount);
        assertThat(afterTouchingEveryCollection).isEqualTo(1 + projectCount);
    }

    // ---------------------------------------------------------------- the round trip itself

    @Test
    void savesAProjectWithTwoTasksAndReadsThemBack() {
        Project project = saveProjectWithTwoTasks("Apollo");
        reset();

        Project reloaded = projectRepository.findById(project.getProjectId()).orElseThrow();
        assertThat(reloaded.getProjectName()).isEqualTo("Apollo");

        List<Task> tasks = taskRepository.findByProject_ProjectId(project.getProjectId());
        assertThat(tasks)
                .hasSize(2)
                .allSatisfy(t -> {
                    assertThat(t.getTaskId()).isNotNull();
                    assertThat(t.getProject().getProjectId()).isEqualTo(project.getProjectId());
                })
                .extracting(Task::getTitle)
                .containsExactlyInAnyOrder("Design the schema", "Write the persistence test");
    }
}
