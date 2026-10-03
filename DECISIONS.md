# Architectural Decisions

Every non-trivial choice, the alternatives considered, and why. Newest at the bottom.

---

## 2026-09-20 — Build tool: Maven

**Alternatives considered:** Gradle

**Why:** More common in enterprise Java shops; matches the project brief's default recommendation. No strong technical reason to prefer either here.

---

## 2026-09-20 — Schema managed by Flyway; `ddl-auto: validate`

**Alternatives considered:** `ddl-auto: update` or `create` (let Hibernate generate the schema from entities)

**Why:** Auto-generated schema hides exactly the complexity this project exists to teach — tenant-first composite indexes, RLS policies, partial indexes, expand-backfill-contract migrations. With `validate`, Hibernate only checks entity mappings against a schema that Flyway created; the schema itself is always hand-written and versioned. This means every entity added from now on requires a matching Flyway migration written by hand — that friction is intentional.

---

## 2026-09-20 — docker-compose starts with Postgres only

**Alternatives considered:** Stand up the full stack (Redis, Kafka, Keycloak, Jaeger, Prometheus, Grafana) up front

**Why:** Phase 1 doesn't touch any of those. Standing up infrastructure before it's used defeats the "feel the problem before applying the pattern" build order — it also makes `docker-compose up` slower and gives more things to debug for zero learning benefit right now. Services get added to the compose file in the phase that first needs them.

---

## 2026-09-26 — Compose Postgres on host port 5433

**Alternatives considered:** Keep 5432 and stop the local Windows Postgres service; use a different host port for the container.

**Why:** A native Postgres install on the dev machine already listens on 5432, so the app authenticated against it instead of the container (`password authentication failed for user "teampulse"`). Mapping the container to host port 5433 avoids touching the machine's existing install. Inside the compose network Postgres still listens on 5432; only the host mapping changed, so anyone else running the project needs the same `5433` URL.

---

## 2026-09-26 — JVM runs in UTC (`-Duser.timezone=UTC`)

**Alternatives considered:** Leave the JVM on the machine's zone (fails: the JDBC driver sends `Asia/Calcutta`, which the Postgres 16 image rejects); use `Asia/Kolkata`; set the zone in `main()`.

**Why:** A multi-tenant backend must not depend on the host's timezone. UTC is the neutral default, and conversion to a tenant's zone belongs at the API edge. Configured in the pom for `spring-boot:run` (`jvmArguments`) and for tests (surefire `argLine`), because a `main()` call wouldn't cover tests. Running from an IDE needs the same VM option (`-Duser.timezone=UTC`).

---
## 2026-09-27 — Persistence tests run on Testcontainers, not the compose Postgres

**Alternatives considered:** Point `@DataJpaTest` at the compose database on 5433; use H2 in-memory.

**Why:** A throwaway container proves the Flyway migration *alone* can build a schema that `ddl-auto: validate` accepts — the compose database has a persistent volume, so it could be passing on state some earlier hand-run created. It also matters for counting SQL: statement counts and N+1 demonstrations only mean something against a database with known contents. H2 was never a candidate; the whole point of Phase 1 is that the dialect is Postgres. The compose database stays for running the app by hand.

**Status:** green. Getting there needed `<testcontainers.version>1.21.4</testcontainers.version>` in the pom: Spring Boot 3.3.4's BOM pins 1.19.8, which cannot reach Docker Desktop 4.53 / Engine 29 and fails every connection strategy with an opaque HTTP 400 on `/info`. The cause is Testcontainers' own Docker discovery, not the bundled docker-java — 1.21.3 fails identically and ships the same docker-java 3.4.2 as 1.21.4. Two plausible wrong diagnoses are written up in INCIDENTS.

---

## 2026-09-27 — Controllers return DTO records, never entities

**Alternatives considered:** Return `Project`/`Task` directly and annotate them with `@JsonIgnore` where needed.

**Why:** Four separate reasons, and it's worth being able to give more than "it's cleaner":

1. **Lazy loading escapes the transaction.** `Task.project` is `LAZY`. If the entity itself were serialised, Jackson would walk that proxy *after* the service's transaction closed, giving `LazyInitializationException` — or, worse, an open-session-in-view workaround that hides N+1 queries inside the serialiser.
2. **The wire format would inherit the schema.** Renaming a column or extracting a table would silently change the JSON. DTOs make that a deliberate edit instead of an accident.
3. **Write-side exposure.** An entity used as a `@RequestBody` lets a caller set any mapped field, including its own id or its project. `TaskRequest` has no id and no project, so those aren't expressible.
4. **Collections leak.** `Project.tasks` would serialise the whole task list on every list call — and back through `Task.project` recursively.

`ProjectResponse` deliberately omits tasks for reason 4; tasks have their own endpoint.

---

## 2026-09-27 — One `ApiError` shape for every failure, via `@RestControllerAdvice`

**Alternatives considered:** RFC 7807 `ProblemDetail`, which Spring 6 supports natively.

**Why:** `ProblemDetail` is the better long-term answer and this should probably migrate to it before the API is public. For now a hand-written record keeps the field-level validation errors in one obvious place (`fieldErrors`, always present, empty when the failure isn't per-field) so a client parses one shape and never branches on status. Revisit when the API gets its first external consumer.

Mapped: 404 unknown id, 400 validation / malformed body / bad UUID, 409 constraint violation, 500 everything else. The 500 handler logs the cause and returns a fixed message — stack traces are not a response body.

---

## 2026-09-27 — `status` is required on `TaskRequest`, and PUT is a full replace

**Alternatives considered:** Optional `status` where absent means "leave unchanged" (PATCH semantics on a PUT); separate create and update records.

**Why:** With an optional `status`, a `PUT` that omits it has to either reset a running task to `NOT_STARTED` or silently keep the old value, and a caller reading the endpoint cannot guess which. Requiring it makes `PUT` an honest full replacement and keeps one request record instead of two nearly identical ones. The `NOT_STARTED` default in the `Task(title, project)` constructor stays as an entity-level default that this API doesn't rely on.

---

## 2026-09-27 — Deleting a project with tasks is a 409 from the database, not a pre-check

**Alternatives considered:** `existsByProject...` check in the service before deleting; cascade the delete to tasks.

**Why:** A pre-check is a race — a task can be inserted between the check and the delete, so the constraint is still the thing that actually decides. Cascading would make `DELETE /api/projects/{id}` silently destroy task rows, which is not something a caller asking to delete one project would expect. The `tasks.project_id` foreign key is left as the single arbiter and its violation is translated to 409.

---
## 2026-10-03 — `findAll()` stays lazy; the N+1 is documented rather than fixed

**Alternatives considered:** Keep `@EntityGraph(attributePaths = "tasks")` on `ProjectRepository.findAll()` (the earlier choice); keep the inherited lazy `findAll()` and add a separate graph-annotated `findAllBy()` for callers that need tasks.

**Why:** The fetch join was solving a problem the API doesn't have. `ProjectResponse` deliberately carries no tasks, so no endpoint ever reads `project.getTasks()` — the graph made `GET /api/projects` join and hydrate every task row only to discard it. A second `findAllBy()` was rejected as speculative: there is no caller for it yet, and an unused query is a thing to maintain and explain.

So `findAll()` is the plain inherited lazy query, and the N+1 it implies is **asserted as present** by `ProjectTaskPersistenceTest#q3b_loadingEveryProjectsTasksLazilyCostsOnePlusNStatements` — 1 select for three projects, then 3 more as each task collection is touched, 4 statements for 6 rows.

That test is a demonstration, not a regression guard. It exists so the cost is a measured number in the suite rather than folklore, and so that whoever later adds a fetch join has to come and change the assertion deliberately instead of quietly shifting the query profile. This is the "feel the problem before applying the pattern" build order applied to a query plan: the fix belongs in the phase that has an endpoint which actually needs tasks alongside projects.

---
