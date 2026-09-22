# TeamPulse — Low-Level Design (LLD)

## Scope

Covers `core-api` exactly as implemented (Phase 1, Step 1: bare CRUD, no tenancy). LLD for later phases (`tenant_id`/RLS, auth-service, outbox, Kafka, other services) is written incrementally as each is actually built — matching the project's own "feel the problem before applying the pattern" order — rather than speculating detail for code that doesn't exist yet. See `docs/HLD.md` for the target end-state architecture.

## 1. Package structure

```
com.teampulse.coreapi
├── CoreApiApplication.java
├── common/
│   ├── ApiError.java              (record: code, message)
│   ├── NotFoundException.java     (RuntimeException)
│   └── GlobalExceptionHandler.java(@RestControllerAdvice)
├── tenant/
│   ├── Tenant.java                (entity; unused by logic yet)
│   └── TenantRepository.java
├── user/
│   ├── User.java                  (entity; no controller)
│   └── UserRepository.java
├── project/
│   ├── Project.java
│   ├── ProjectRepository.java
│   ├── ProjectRequest.java        (record DTO)
│   ├── ProjectResponse.java       (record DTO)
│   └── ProjectController.java
└── task/
    ├── Task.java
    ├── TaskStatus.java            (enum: TODO, IN_PROGRESS, DONE)
    ├── TaskRepository.java
    ├── TaskRequest.java           (record DTO)
    ├── TaskResponse.java          (record DTO)
    └── TaskController.java
```

## 2. Database schema (as migrated by `V1__init_schema.sql`)

| Table | Column | Type | Constraints |
|---|---|---|---|
| `tenants` | `id` | UUID | PK |
| | `name` | VARCHAR(255) | NOT NULL |
| | `slug` | VARCHAR(100) | UNIQUE, NOT NULL |
| | `tier` | VARCHAR(50) | NOT NULL |
| | `isolation_mode` | VARCHAR(50) | NOT NULL |
| | `status` | VARCHAR(50) | NOT NULL |
| | `created_at` | TIMESTAMPTZ | NOT NULL, default now() |
| `users` | `id` | UUID | PK |
| | `email` | VARCHAR(255) | UNIQUE, NOT NULL |
| | `role` | VARCHAR(50) | NOT NULL |
| | `created_at` | TIMESTAMPTZ | NOT NULL, default now() |
| `projects` | `id` | UUID | PK |
| | `name` | VARCHAR(255) | NOT NULL |
| | `created_at` | TIMESTAMPTZ | NOT NULL, default now() |
| `tasks` | `id` | UUID | PK |
| | `project_id` | UUID | FK → projects(id), NOT NULL |
| | `title` | VARCHAR(500) | NOT NULL |
| | `status` | VARCHAR(50) | NOT NULL |
| | `assignee_id` | UUID | FK → users(id), nullable |
| | `version` | BIGINT | NOT NULL, default 0 |
| | `created_at` | TIMESTAMPTZ | NOT NULL, default now() |
| | `updated_at` | TIMESTAMPTZ | NOT NULL, default now() |

Index: `idx_tasks_project ON tasks (project_id)`. Seed data: two `users` rows (`alice@example.com`, `bob@example.com`, fixed UUIDs `...0001`/`...0002`) so `assignee_id` has something to reference without a User REST API.

## 3. Entity ↔ JPA mapping details

- **ID generation**: every entity assigns `private UUID id = UUID.randomUUID();` as a field initializer — no `@GeneratedValue`, no DB-side default. Deliberate: avoids requiring the `pgcrypto` extension (see `DECISIONS.md`).
- **`Task` → `Project`**: `@ManyToOne(optional = false)` + `@JoinColumn(name = "project_id", nullable = false)`.
- **`Task` → `User` (assignee)**: `@ManyToOne` (optional) + `@JoinColumn(name = "assignee_id")`, nullable.
- **Optimistic locking**: `Task.version` is `@Version`; a stale write throws `OptimisticLockingFailureException` (currently unhandled by `GlobalExceptionHandler` — falls through to Spring Boot's default 500; not exercised in Step 1's scope since there's no concurrent-write test).
- **`updatedAt` maintenance**: `Task` has a `@PreUpdate` callback (`onUpdate()`) that stamps `updatedAt = OffsetDateTime.now()` on every JPA update.
- **Encapsulation**: all entities have a protected no-arg constructor (JPA requirement) plus a public constructor taking only the fields a caller should set; mutation goes through explicit setters (`setName`, `setTitle`, `setStatus`, `setAssignee`) — no setter exists for `id`, `createdAt`, or `version`.

## 4. REST API contract

### `/api/projects`

| Method | Path | Request body | Success | Failure |
|---|---|---|---|---|
| POST | `/api/projects` | `{ "name": string }` (`@NotBlank`) | 201 `ProjectResponse` | 400 `ApiError` (blank name) |
| GET | `/api/projects` | — | 200 `ProjectResponse[]` | — |
| GET | `/api/projects/{id}` | — | 200 `ProjectResponse` | 404 `ApiError` |
| PUT | `/api/projects/{id}` | `{ "name": string }` | 200 `ProjectResponse` | 404 / 400 |
| DELETE | `/api/projects/{id}` | — | 204 | 404 `ApiError` |

`ProjectResponse`: `{ id: uuid, name: string, createdAt: ISO-8601 }`

### `/api/tasks`

| Method | Path | Request body | Success | Failure |
|---|---|---|---|---|
| POST | `/api/tasks` | `{ projectId: uuid, title: string, status: TODO\|IN_PROGRESS\|DONE, assigneeId?: uuid }` | 201 `TaskResponse` | 404 (bad `projectId`/`assigneeId`) / 400 (blank title / missing required field) |
| GET | `/api/tasks` | — | 200 `TaskResponse[]` | — |
| GET | `/api/tasks/{id}` | — | 200 `TaskResponse` | 404 |
| PUT | `/api/tasks/{id}` | same as POST | 200 `TaskResponse` | 404 / 400 |
| DELETE | `/api/tasks/{id}` | — | 204 | 404 |

`TaskResponse`: `{ id, projectId, title, status, assigneeId (nullable), version, createdAt, updatedAt }`

`ApiError`: `{ code: "NOT_FOUND" | "VALIDATION_FAILED", message: string }`

## 5. Control flow — `POST /api/tasks` (representative sequence)

```
Client → TaskController.create(TaskRequest)
  1. @Valid triggers Bean Validation (projectId/title/status @NotNull/@NotBlank)
     → on failure: MethodArgumentNotValidException → GlobalExceptionHandler → 400 ApiError
  2. findProjectOrThrow(projectId): ProjectRepository.findById
     → on empty: NotFoundException("Project {id} not found") → GlobalExceptionHandler → 404 ApiError
  3. resolveAssignee(assigneeId): null passthrough, or UserRepository.findById
     → on empty (non-null id given): NotFoundException → 404 ApiError
  4. new Task(project, title, status, assignee) — id/version/timestamps self-initialize
  5. TaskRepository.save(task) — Hibernate INSERT, Flyway-validated schema
  6. TaskResponse.from(task) → 201 Created
```

`update`, `delete`, `get` follow the same `findOrThrow` pattern; `update` additionally re-resolves `assignee` on every call (no partial-update semantics — full replace of mutable fields).

## 6. Exception handling design

`GlobalExceptionHandler` (`@RestControllerAdvice`, `common/`):
- `NotFoundException` → 404, `ApiError("NOT_FOUND", message)`.
- `MethodArgumentNotValidException` → 400, `ApiError("VALIDATION_FAILED", "<field> <default message>")` — takes the *first* field error only (kept deliberately simple; not aggregating all violations).
- No handler yet for `OptimisticLockingFailureException` or generic `Exception` — both fall through to Spring Boot's default error response. Flagged as a gap to close if/when concurrent-update behavior is exercised (the project doc's §7b calls out 409-for-optimistic-lock as a pattern worth adding later).

## 7. Validation rules

| DTO | Field | Rule | Enforced by |
|---|---|---|---|
| `ProjectRequest` | `name` | non-blank | `@NotBlank` |
| `TaskRequest` | `projectId` | required | `@NotNull` |
| `TaskRequest` | `title` | non-blank | `@NotBlank` |
| `TaskRequest` | `status` | required, must be a valid `TaskStatus` enum value | `@NotNull` + Jackson enum deserialization (invalid string → 400 via Spring's default message-not-readable handling, not currently mapped to `ApiError`) |
| `TaskRequest` | `assigneeId` | optional | none (nullable) |

## 8. Testing design

`ProjectTaskFlowTests` (`src/test/java/.../ProjectTaskFlowTests.java`):
- `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers`, with a `@Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")` — Spring Boot's `@ServiceConnection` auto-wires the datasource, no manual `@DynamicPropertySource` needed.
- Test 1 (`createReadUpdateDeleteProjectAndTask`): full golden path — POST project → POST task → GET → PUT (status → DONE) → DELETE → GET returns 404.
- Test 2 (`rejectsBlankProjectName`): POST with `{"name":""}` → 400.
- Known local limitation: this test currently cannot execute on the dev machine due to a Docker Desktop named-pipe API incompatibility (confirmed independent of the code via direct `mvn spring-boot:run` + curl verification of the identical flow) — not a defect in the test or app code; expected to pass in CI/any standard Docker environment.

## 9. Out of scope for this LLD

`tenant_id` columns, RLS policies, `SET LOCAL` tenant-context propagation, the connection-pool leak bug, isolation tests, auth/JWT, Kafka/outbox, and every other service in the HLD topology — each gets its own LLD once its implementation plan is written.
