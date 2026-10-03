package com.teampulse.coreapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Black-box HTTP tests for the projects/tasks API.
 *
 * Driven with MockMvc rather than TestRestTemplate on a random port. MockMvc still runs the
 * whole web stack that these assertions are actually about - DispatcherServlet, argument
 * resolvers, Bean Validation, RestControllerAdvice, Jackson - but skips the Tomcat
 * connector, so there is no port to allocate, no socket timeout to tune, and a failure shows
 * the parsed response instead of a connection error. What it does NOT cover: real socket and
 * connector behaviour, servlet filter ordering as deployed, and container-level error pages.
 * None of those are under test here.
 *
 * Deliberately NOT annotated Transactional. SpringBootTest, unlike DataJpaTest, gives no test
 * transaction by default, and that is load-bearing for the 409 case: if the test held a
 * transaction, the service's own Transactional would join it, the foreign key violation would
 * only surface when the test rolled back, and the delete would appear to succeed. Isolation
 * between tests comes from truncating instead.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ProjectTaskIntegrationTest {

    private static final String UUID_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearTables() {
        // Teardown, not an assertion: every test builds its own fixture over HTTP.
        jdbcTemplate.execute("TRUNCATE TABLE tasks, projects CASCADE");
    }

    // ---------------------------------------------------------------------- helpers

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private UUID createProject(String name) throws Exception {
        ResultActions result = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectName\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
        return UUID.fromString(body(result).get("projectId").asText());
    }

    private UUID createTask(UUID projectId, String json) throws Exception {
        ResultActions result = mockMvc.perform(post("/api/projects/" + projectId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
        return UUID.fromString(body(result).get("taskId").asText());
    }

    // ---------------------------------------------------------------------- a. create project

    @Test
    void createProjectReturns201WithLocationAndProjectResponseShape() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectName\":\"Apollo\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/projects/" + UUID_REGEX)))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.projectId", matchesPattern(UUID_REGEX)))
                .andExpect(jsonPath("$.projectName").value("Apollo"))
                // ProjectResponse is exactly these two fields: no tasks, no leaked entity state.
                .andExpect(jsonPath("$.*", hasSize(2)))
                .andExpect(jsonPath("$.tasks").doesNotExist());
    }

    @Test
    void locationHeaderOfACreatedProjectIsFetchable() throws Exception {
        String location = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectName\":\"Gemini\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("Gemini"));
    }

    // ---------------------------------------------------------------------- b. create task

    @Test
    void createTaskUnderAProjectReturns201() throws Exception {
        UUID projectId = createProject("Apollo");

        mockMvc.perform(post("/api/projects/" + projectId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Design the schema\",\"status\":\"NOT_STARTED\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/tasks/" + UUID_REGEX)))
                .andExpect(jsonPath("$.taskId", matchesPattern(UUID_REGEX)))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.title").value("Design the schema"))
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.*", hasSize(8)));
    }

    // ---------------------------------------------------------------------- c. round trip

    @Test
    void everyTaskFieldSurvivesTheRoundTrip() throws Exception {
        UUID projectId = createProject("Apollo");
        UUID taskId = createTask(projectId, """
                {
                  "title": "Design the schema",
                  "description": "projects and tasks, with the FK",
                  "notes": "see V1 migration",
                  "startDate": "2026-09-01",
                  "endDate": "2026-09-10",
                  "status": "IN_PROGRESS"
                }
                """);

        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.title").value("Design the schema"))
                .andExpect(jsonPath("$.description").value("projects and tasks, with the FK"))
                .andExpect(jsonPath("$.notes").value("see V1 migration"))
                .andExpect(jsonPath("$.startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.endDate").value("2026-09-10"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.*", hasSize(8)));
    }

    @Test
    void listingTasksOfAProjectReturnsOnlyThatProjectsTasks() throws Exception {
        UUID apollo = createProject("Apollo");
        UUID gemini = createProject("Gemini");
        createTask(apollo, "{\"title\":\"Apollo one\",\"status\":\"NOT_STARTED\"}");
        createTask(apollo, "{\"title\":\"Apollo two\",\"status\":\"COMPLETED\"}");
        createTask(gemini, "{\"title\":\"Gemini one\",\"status\":\"NOT_STARTED\"}");

        mockMvc.perform(get("/api/projects/" + apollo + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].title", containsInAnyOrder("Apollo one", "Apollo two")))
                .andExpect(jsonPath("$[*].projectId", everyItem(is(apollo.toString()))));
    }

    // ---------------------------------------------------------------------- d. 404 shape

    @Test
    void listingTasksOfAMissingProjectReturns404InTheApiErrorShape() throws Exception {
        UUID missing = UUID.randomUUID();
        String path = "/api/projects/" + missing + "/tasks";

        mockMvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Project " + missing + " not found"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.timestamp").exists())
                // fieldErrors is present and empty rather than absent, so a client parses one
                // shape for every failure instead of branching on the status.
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors", hasSize(0)))
                .andExpect(jsonPath("$.*", hasSize(6)));
    }

    @Test
    void aMissingTaskAndAMissingProjectShareTheSameErrorShape() throws Exception {
        for (String path : new String[]{
                "/api/tasks/" + UUID.randomUUID(),
                "/api/projects/" + UUID.randomUUID()}) {

            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.path").value(path))
                    .andExpect(jsonPath("$.fieldErrors", hasSize(0)))
                    .andExpect(jsonPath("$.*", hasSize(6)));
        }
    }

    // ---------------------------------------------------------------------- e. validation

    @Test
    void aBlankTitleReturns400NamingTheOffendingField() throws Exception {
        UUID projectId = createProject("Apollo");
        String path = "/api/projects/" + projectId + "/tasks";

        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \",\"status\":\"NOT_STARTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("title must not be blank"))
                .andExpect(jsonPath("$.*", hasSize(6)));
    }

    @Test
    void aMissingStatusIsRejectedBeforeItReachesTheDatabase() throws Exception {
        UUID projectId = createProject("Apollo");

        mockMvc.perform(post("/api/projects/" + projectId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"No status given\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("status")));

        // Nothing was written: the project still has no tasks.
        mockMvc.perform(get("/api/projects/" + projectId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anEndDateBeforeTheStartDateIsRejected() throws Exception {
        UUID projectId = createProject("Apollo");

        mockMvc.perform(post("/api/projects/" + projectId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Bad dates\",\"status\":\"NOT_STARTED\","
                                + "\"startDate\":\"2026-09-10\",\"endDate\":\"2026-09-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].message",
                        hasItem("endDate must not be before startDate")));
    }

    // ---------------------------------------------------------------------- f. 409, and no leaks

    @Test
    void deletingAProjectThatStillHasATaskReturns409AndLeaksNothing() throws Exception {
        UUID projectId = createProject("Apollo");
        createTask(projectId, "{\"title\":\"Design the schema\",\"status\":\"NOT_STARTED\"}");
        String path = "/api/projects/" + projectId;

        String responseBody = mockMvc.perform(delete(path))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message")
                        .value("The request conflicts with the current state of the data"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.fieldErrors", hasSize(0)))
                // Exactly the six ApiError fields: no "trace", no "exception", no "cause".
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andReturn().getResponse().getContentAsString();

        // The whole body, not just the message: nothing about the schema or the stack reaches
        // the caller. Asserted on the response text so it holds for any field added later.
        assertThat(responseBody).doesNotContainIgnoringCase(
                "task",                 // table name (also covers "tasks")
                "project_id",           // column name
                "constraint",
                "foreign key",
                "violates",
                "sql",
                "exception",
                "hibernate",
                "postgres",
                "org.springframework",
                "\tat ");               // stack frame
    }

    @Test
    void theProjectSurvivesTheRejectedDeleteAndGoesOnceItsTaskIsGone() throws Exception {
        UUID projectId = createProject("Apollo");
        UUID taskId = createTask(projectId, "{\"title\":\"Design the schema\",\"status\":\"NOT_STARTED\"}");

        mockMvc.perform(delete("/api/projects/" + projectId)).andExpect(status().isConflict());

        // The failed delete rolled back cleanly rather than half-applying.
        mockMvc.perform(get("/api/projects/" + projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("Apollo"));

        mockMvc.perform(delete("/api/tasks/" + taskId)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/projects/" + projectId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/projects/" + projectId)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------- malformed input

    @Test
    void aNonUuidPathVariableAndAnUnknownStatusAreBoth400InTheSameShape() throws Exception {
        mockMvc.perform(get("/api/projects/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'not-a-uuid' is not a valid value for projectId"))
                .andExpect(jsonPath("$.*", hasSize(6)));

        UUID projectId = createProject("Apollo");
        mockMvc.perform(post("/api/projects/" + projectId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"status\":\"BANANA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.*", hasSize(6)));
    }
}
