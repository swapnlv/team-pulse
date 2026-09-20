# Multi-Tenant SaaS Platform — Architect-Level Learning Project

**Purpose:** A single cohesive project that forces you to implement and reason about the architectural patterns expected of a Senior/Lead/Architect-level Java backend engineer.

**Target outcome:** Not a portfolio toy. A system where you have personally hit — and fixed — the failure modes that interview questions are really about.

---

## 1. Why Multi-Tenant SaaS (and not the other options)

| Alternative | Why Multi-Tenant SaaS beats it *for you* |
|---|---|
| Ticket booking | Broader patterns, but the multi-tenant problem is closer to your COP day job and to 80% of enterprise Java roles |
| Payment/wallet | Stronger correctness story, but narrower — most job descriptions aren't payments |
| URL shortener | Deep on scale, thin on business workflows, RBAC, and tenancy |
| Food delivery | Great for geospatial/real-time, weak on isolation and compliance thinking |

**The decisive reason:** multi-tenancy is an *architectural decision*, not a feature. Every layer — API gateway, application logic, database queries, and background jobs — must be tenant-aware from day one. That's exactly the kind of cross-cutting, "decide once and live with it" call that separates an architect from a senior developer. And you already work on a multi-tenant system (COP), so what you learn here transfers directly into stronger answers about your actual job.

---

## 2. Problem Statement

### The business scenario

You are building **"TeamPulse"** — a B2B SaaS project-and-task management platform (think a focused Jira/Asana). Multiple customer organizations (tenants) sign up. Each tenant has its own users, projects, tasks, comments, and attachments. Tenants must never see each other's data. Tenants are on different pricing tiers with different limits and features.

### Why this scenario justifies serious architecture

Each requirement below is a real product requirement that *forces* a specific architectural pattern. Nothing here is bolted on for learning's sake.

| Product requirement | Architectural pattern it forces |
|---|---|
| Acme Corp must never see Globex Corp's tasks — not through a bug, not through a missing WHERE clause | **Tenant isolation**, PostgreSQL Row-Level Security, tenant context propagation |
| A user logs in once and accesses only their org's data with their role's permissions | **OAuth2/OIDC, JWT, refresh token rotation, RBAC** |
| Free tier gets 100 API calls/min; Enterprise gets 10,000 | **Per-tenant rate limiting**, quota enforcement |
| One tenant running a huge report must not slow everyone else down | **Noisy neighbour mitigation**, connection pool tuning, query budgets, bulkheads |
| Task assigned → notify assignee by email, without blocking the API response | **Async messaging (Kafka)**, event-driven architecture |
| A task update must be saved AND its event published — never one without the other | **Outbox pattern** |
| Notification service may redeliver the same event after a crash | **Idempotent consumers**, at-least-once semantics |
| Org admins want a dashboard of team activity without slowing down live task writes | **CQRS** — separate read model |
| The email provider goes down; task creation must still work | **Circuit breaker, fallback, bulkhead** (Resilience4j) |
| Tenants want to see task board updates live as teammates edit | **WebSocket/SSE**, real-time push |
| We bill each tenant based on usage | **Usage metering**, aggregation pipeline |
| New enterprise customer needs a fully isolated database | **Hybrid tenancy model**, tenant routing |
| Support asks "why was this tenant's API slow at 3pm yesterday?" | **Distributed tracing, per-tenant metrics, structured logging with correlation IDs** |
| Ship features weekly without downtime | **CI/CD, canary/blue-green deploys, feature flags (per-tenant)** |
| Onboarding a new tenant must be automatic, not a manual DB script | **Provisioning automation**, schema migration strategy |

---

## 3. The Central Architectural Decision: Tenant Isolation Model

This is the single most consequential decision in the whole project, and the one interviewers probe hardest. Get fluent in all three models and the reasoning behind choosing one.

### The three models

**A. Shared database, shared schema (Pool model)**
All tenants share the same tables; a `tenant_id` column on every table distinguishes ownership. Highest density, lowest cost per tenant — but it places the burden of isolation on the application layer, making it the most demanding to secure.

**B. Shared database, separate schemas (Bridge model)**
Tenants share a database instance but each gets its own set of tables. A moderate level of isolation at higher density. Per-tenant backup/restore becomes straightforward. However, this is increasingly considered a middle ground that's rarely the right pick for a *new* SaaS application today — it inherits migration complexity without the full isolation benefit.

**C. Database per tenant (Silo model)**
Each tenant gets dedicated resources — a separate database or full infrastructure stack. Strongest isolation, no contention between tenants, but the lowest density and highest operational cost.

### What to build, and why

**Build the Pool model (shared schema) with PostgreSQL Row-Level Security as the enforcement layer, plus a documented path to Silo for enterprise tenants.**

This reflects current practice: pooled architecture with PostgreSQL RLS has become the dominant default for new SaaS applications, with a roughly 3–5x reduction in infrastructure cost of goods sold compared to fully siloed, database-per-tenant architectures. The recommended progression is to start with shared database + row-level isolation (it scales to thousands of tenants with the lowest operational overhead) and move to database-per-tenant only when regulation demands it.

The hybrid pattern is what most mature products land on: shared schema for the long tail of customers, dedicated DB for regulated or high-revenue tenants, with the tiering built into pricing. Build the shared path first, then add hybrid routing — that ordering teaches you both, and the migration between them.

### Why RLS matters more than a WHERE clause

Adding a `tenant_id` column and filtering in application code *seems* easy. But it puts the entire security burden on the application — a single missing WHERE clause exposes another customer's data.

Row-Level Security moves isolation into the database: SQL-defined policies systematically filter rows, eliminating the need for application code to intervene. Critically, **tables with RLS enabled block access by default when no policies are defined**, which drastically reduces the risk from implementation oversights in the API or backend layer.

```sql
-- Enable RLS on a table
ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;

-- Policy: a session can only see rows matching its tenant context
CREATE POLICY tenant_isolation ON tasks
  USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
```

```java
// Spring Boot: set the tenant context at the start of each request transaction.
// SET LOCAL scopes the setting to the current transaction — exactly matching
// a request lifecycle, so it cannot leak into the next request on a pooled connection.
@Component
public class TenantContextSetter {
    private final JdbcTemplate jdbcTemplate;

    public void setCurrentTenant(UUID tenantId) {
        jdbcTemplate.update("SET LOCAL app.current_tenant_id = ?", tenantId.toString());
    }
}
```

**The critical implementation pitfall to learn firsthand:** connection pooling. Because HikariCP reuses connections across requests, a tenant setting applied with plain `SET` (not `SET LOCAL`) persists on that connection and leaks into the *next* request that borrows it — a genuine cross-tenant data leak. Build this bug deliberately, observe it, then fix it. That experience is worth more than reading about it ten times.

**A second discipline worth adopting:** write automated isolation tests that assert tenant A cannot read tenant B's rows, and run them in CI on every PR — isolation bugs compound, because once merged they silently affect every feature built afterwards. Isolation bugs don't announce themselves; they leak quietly, often for weeks or months, until a curious user or a penetration test finds them.

---

## 4. Tech Stack (2026-appropriate)

### Core

| Layer | Choice | Reasoning |
|---|---|---|
| Language | **Java 21 LTS** (or Java 25 LTS) | Java 21 is widely deployed in enterprises due to conservative upgrade cycles; Java 25 LTS shipped September 2025 and is the target for new projects. Java 21 is the safer default for job-relevance; Java 25 if you want the newest finalized features. |
| Framework | **Spring Boot 3.x** | Current mainstream. Note Spring Boot 4 requires Java 17 minimum. |
| Build | Maven (or Gradle) | Either is fine; Maven is more common in enterprise Java shops |
| Primary DB | **PostgreSQL 15+** | Required for the RLS approach; also gives you `pg_stat_statements` for query analysis |
| Cache / locks | **Redis** | Cache-aside, distributed rate limiting, distributed locks, session store |
| Message broker | **Apache Kafka** | Matches your existing experience; teaches partitions, consumer groups, offsets |
| Migrations | **Flyway** or **Liquibase** | Versioned schema, expand-backfill-contract migrations |

### Observability

| Concern | Tool |
|---|---|
| Tracing | OpenTelemetry + Jaeger |
| Metrics | Micrometer → Prometheus → Grafana |
| Logging | Logback with structured JSON output + correlation IDs (and tenant IDs) |

### Resilience & security

- **Resilience4j** — circuit breaker, rate limiter, bulkhead, retry
- **Spring Security + OAuth2 Resource Server** — JWT validation
- **Keycloak** (self-hosted, free) as the identity provider — gives you a real OAuth2/OIDC authorization server to integrate against, rather than hand-rolling one

### Deployment

- **Docker** + **docker-compose** for local development (Postgres, Redis, Kafka, Keycloak, Jaeger, Prometheus, Grafana all in one file)
- **Kubernetes** (Minikube/k3s locally) — teaches liveness/readiness probes, resource limits, HPA
- **GitHub Actions** — CI/CD pipeline with test → build → scan → deploy stages

### Modern Java features to deliberately use

These are increasingly expected and are worth using intentionally rather than incidentally:

- **Virtual Threads (Project Loom)** — finalized in Java 21, described as the most significant concurrency upgrade in Java in decades. Use them for your I/O-bound request handling and compare throughput against a traditional platform-thread pool. Learn about **thread pinning** — the known pitfall where a virtual thread blocks its carrier thread (e.g. inside a `synchronized` block).
- **Structured Concurrency** — finalized in Java 25 (JEP 505 after 5 previews). Use it when a request fans out to several downstream calls that should be cancelled together if one fails.
- **Scoped Values (JEP 506, finalized in Java 25)** — a safer, more performant alternative to `ThreadLocal` for sharing immutable data within a thread and its child threads, and critical for virtual thread workloads where `ThreadLocal` becomes expensive. **This is directly relevant here: your tenant context is exactly the kind of data that would traditionally live in a `ThreadLocal`.**
- **Records** — for DTOs and events
- **Sealed classes** — for modelling closed sets of domain states
- **GraalVM Native Image** — no longer experimental, now production-ready and expected in job descriptions. Compile one service natively and measure startup time and memory against the JVM version.

---

## 5. Service Breakdown

Seven services, but **do not build them all at once** — see the build order in section 7.

### 1. `tenant-service` (the heart of the project)
- Tenant registration, onboarding, provisioning automation
- Tenant metadata: tier (Free/Pro/Enterprise), isolation mode (pooled vs dedicated), status
- Per-tenant configuration and feature flags
- Tenant routing logic: given a tenant, which datasource does it use?
- **Teaches:** provisioning automation, hybrid tenancy, tenant lifecycle

### 2. `auth-service`
- Integrates with Keycloak as the OAuth2/OIDC authorization server
- JWT issuance with `tenant_id` and `roles` claims
- **Refresh token rotation with reuse detection** (revoke the token family on reuse)
- RBAC: `TENANT_ADMIN`, `PROJECT_MANAGER`, `MEMBER`, `VIEWER`
- **Teaches:** OAuth2 flows, token rotation, JWKS/key rotation, RBAC, zero-trust thinking

### 3. `core-api` (projects & tasks — the main business service)
- CRUD for projects, tasks, comments
- Tenant context filter: extract `tenant_id` from JWT → set request context → `SET LOCAL` on the DB transaction
- Optimistic locking (`@Version`) on task updates
- Cache-aside with Redis (tenant-scoped cache keys — a leak here is a data breach)
- Outbox table written in the same transaction as business data
- **Teaches:** RLS, tenant context propagation, caching, locking, outbox

### 4. `outbox-relay`
- Polls the outbox table, publishes to Kafka, marks rows as sent
- Uses `FOR UPDATE SKIP LOCKED` so multiple relay instances can claim batches atomically without publishing duplicates
- **Teaches:** outbox pattern internals, distributed coordination, at-least-once publishing

### 5. `notification-service`
- Kafka consumer; sends email/in-app notifications
- **Idempotent processing** keyed on event ID (dedup table + unique constraint)
- Circuit breaker around the external email provider
- Dead letter queue for poison messages
- **Teaches:** Kafka consumer groups, idempotency, circuit breaker, DLQ

### 6. `analytics-service` (CQRS read side)
- Consumes the same Kafka event stream, builds denormalized read models
- Serves org dashboards — heavy read queries that never touch the write database
- **Teaches:** CQRS, eventual consistency, read model design, read replicas

### 7. `metering-service`
- Tracks per-tenant API calls, storage, active users
- Aggregates usage for billing and quota enforcement
- Feeds per-tenant rate limits back to the gateway
- **Teaches:** usage metering, quota enforcement, aggregation pipelines

### Plus: `api-gateway`
- Spring Cloud Gateway: routing, JWT validation at the edge, **per-tenant rate limiting** (Redis-backed token bucket), request logging with correlation IDs
- **Teaches:** gateway patterns, distributed rate limiting, edge security

---

## 6. Core Schema Sketch

```sql
-- Every tenant-owned table carries tenant_id and has an RLS policy + index on it
CREATE TABLE tenants (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) UNIQUE NOT NULL,
    tier            VARCHAR(50) NOT NULL,        -- FREE | PRO | ENTERPRISE
    isolation_mode  VARCHAR(50) NOT NULL,        -- POOLED | DEDICATED
    datasource_ref  VARCHAR(255),                -- null when POOLED
    status          VARCHAR(50) NOT NULL,        -- ACTIVE | SUSPENDED | PROVISIONING
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    email       VARCHAR(255) NOT NULL,
    role        VARCHAR(50) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)                    -- email unique per tenant, not globally
);

CREATE TABLE projects (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tasks (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenants(id),
    project_id    UUID NOT NULL REFERENCES projects(id),
    title         VARCHAR(500) NOT NULL,
    status        VARCHAR(50) NOT NULL,
    assignee_id   UUID REFERENCES users(id),
    version       BIGINT NOT NULL DEFAULT 0,     -- optimistic locking
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The composite index that almost every query will use.
-- Tenant-first ordering matters: every query filters by tenant.
CREATE INDEX idx_tasks_tenant_project ON tasks (tenant_id, project_id, status);

-- Outbox: written in the SAME transaction as the business data
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,        -- e.g. 'Task'
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(100) NOT NULL,        -- e.g. 'TaskAssigned'
    payload        JSONB NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON outbox_events (status, created_at) WHERE status = 'PENDING';

-- Consumer-side dedup for idempotency
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,               -- unique constraint IS the safety net
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Usage metering
CREATE TABLE usage_records (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    metric      VARCHAR(100) NOT NULL,           -- API_CALLS | STORAGE_BYTES | ACTIVE_USERS
    value       BIGINT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_usage_tenant_window ON usage_records (tenant_id, metric, window_start);
```

**Schema design lessons baked in here:**
- `tenant_id` first in every composite index — because every query filters on it
- `UNIQUE (tenant_id, email)` not `UNIQUE (email)` — the same person can exist in two tenants
- Partial index on the outbox (`WHERE status = 'PENDING'`) — keeps the index small as sent rows accumulate
- `processed_events` unique constraint as the last line of defence for idempotency, not just an application-level check

---

## 7. Build Order (the part that actually matters)

Building in the wrong order teaches you far less. The principle: **feel the problem before you apply the pattern.**

### Phase 1 — Monolith with tenancy (weeks 1–2)
Build `core-api` as a single Spring Boot app with Postgres.
1. Projects/tasks CRUD, no tenancy at all
2. Add `tenant_id` columns and application-level filtering
3. **Deliberately introduce a missing-WHERE-clause bug** and watch tenant data leak
4. Add RLS policies, then re-run the same broken code — observe the database refusing to leak
5. **Deliberately use `SET` instead of `SET LOCAL`** and watch tenant context leak across pooled connections
6. Write automated isolation tests and wire them into CI

**What you'll be able to explain afterwards:** why database-enforced isolation beats application-enforced, with a story about a bug you actually caused and caught.

### Phase 2 — Auth & authorization (week 3)
1. Stand up Keycloak in docker-compose
2. Integrate Spring Security OAuth2 resource server, validate JWTs
3. Extract `tenant_id` from the token → set tenant context (try Scoped Values instead of ThreadLocal)
4. Implement RBAC with method-level security
5. Implement refresh token rotation with reuse detection

### Phase 3 — Async & events (weeks 4–5)
1. Add Kafka; publish a `TaskAssigned` event directly from `core-api` after saving
2. **Deliberately crash the app between DB commit and Kafka publish** — observe the event lost forever
3. Now implement the outbox pattern to fix it, and build `outbox-relay`
4. Build `notification-service` as a consumer
5. **Deliberately redeliver an event** — observe a duplicate notification
6. Add idempotent consumption keyed on event ID

**This sequence is the single highest-value part of the project.** You'll be able to explain outbox and idempotency from experience rather than from a blog post.

### Phase 4 — Scale & resilience (week 6)
1. Per-tenant rate limiting at the gateway (Redis token bucket)
2. Cache-aside with Redis, tenant-scoped keys — and deliberately forget the tenant prefix once to see the leak
3. Circuit breaker + bulkhead around the email provider; simulate it going down
4. Load test with a "noisy neighbour" tenant hammering the API; observe the impact on other tenants; tune connection pools

### Phase 5 — Observability (week 7)
1. OpenTelemetry tracing across gateway → core-api → Kafka → notification-service
2. Structured JSON logging with correlation ID *and* tenant ID on every line
3. Per-tenant metrics (p95 latency by tenant) in Grafana
4. Reproduce a "why was tenant X slow at 3pm" investigation using only your own dashboards

### Phase 6 — CQRS & metering (week 8)
1. `analytics-service` consuming the event stream into a denormalized read model
2. `metering-service` aggregating per-tenant usage
3. Wire quotas back into the gateway's rate limiter

### Phase 7 — Hybrid tenancy & delivery (weeks 9–10)
1. Add `DEDICATED` isolation mode; route an "enterprise" tenant to its own database
2. **Migrate a tenant from pooled to dedicated** — this is the hardest and most instructive task in the project
3. Kubernetes manifests: probes, resource limits, HPA
4. GitHub Actions pipeline: test → build → scan → deploy, with a canary stage
5. Per-tenant feature flags

---

## 7b. Spring Boot Core Concepts — Where Each One Lives in This Project

The architecture patterns above are what makes you an architect. These are what makes you a *Java* architect — and they're what a Spring Boot interviewer will actually grill you on. Every one has a natural home in this project.

### Transaction management (`@Transactional`)

This project is unusually good for learning transactions properly, because tenant context and the outbox both depend on transaction boundaries being correct.

**Where it lives:**
- **Tenant context + `SET LOCAL`** — the whole RLS mechanism depends on knowing exactly when a transaction starts and ends. `SET LOCAL` is scoped to the transaction; get the boundary wrong and the context is either missing or leaked.
- **Outbox write** — the business row and the outbox row must be in *one* transaction. This is the entire point of the pattern.
- **Idempotent consumer** — the dedup check and the effect must be atomic, or two concurrent retries both pass the check.

**Concepts to deliberately exercise:**

```java
// Propagation — what happens when a transactional method calls another
@Transactional(propagation = Propagation.REQUIRED)      // default: join existing, or create
@Transactional(propagation = Propagation.REQUIRES_NEW)  // suspend outer, start a fresh one
@Transactional(propagation = Propagation.NESTED)        // savepoint within the outer transaction

// Isolation — what concurrent transactions can see
@Transactional(isolation = Isolation.READ_COMMITTED)    // Postgres default
@Transactional(isolation = Isolation.REPEATABLE_READ)   // prevents non-repeatable reads
@Transactional(isolation = Isolation.SERIALIZABLE)      // strongest, slowest

// Rollback rules — the classic trap
@Transactional(rollbackFor = Exception.class)  // by default, Spring rolls back on RuntimeException
                                               // and Error ONLY — checked exceptions do NOT
                                               // trigger rollback unless you say so

// Read-only hint — useful on your analytics-service queries
@Transactional(readOnly = true)
```

**Deliberate experiments to run (this is how it sticks):**

1. **The self-invocation trap.** Call a `@Transactional` method from another method *in the same class* and watch the transaction not start. Because Spring wraps your bean in a proxy, an internal call bypasses the proxy entirely. Fix it by moving the method to another bean, or by self-injecting the proxy. This is one of the most commonly asked Spring questions and almost nobody has actually seen it happen.

2. **The checked-exception rollback trap.** Throw a checked exception from inside a `@Transactional` method and watch the transaction *commit* anyway. Then add `rollbackFor`.

3. **Pausing a transaction.** Put a `Thread.sleep()` inside a `@Transactional` method that holds a pessimistic lock, then hit the same row from a second request. Watch it block. This teaches the real lesson: **a transaction holds its locks for its entire duration**, which is why a long-running transaction is dangerous — it isn't the slowness, it's the lock held the whole time.

4. **`REQUIRES_NEW` for audit logging.** Write an audit record that must persist *even if the main business transaction rolls back*. This is the canonical legitimate use of `REQUIRES_NEW`, and it's a great interview example because it shows you know when to break the default.

### Global exception handling (`@ControllerAdvice`)

Every service needs this, and it's a strong place to show engineering maturity.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<ApiError> handleTenantNotFound(TenantNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError.of("TENANT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentUpdate(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)   // 409 — the correct code, not 500
            .body(ApiError.of("CONCURRENT_UPDATE", "This task was modified by someone else."));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
            .body(ApiError.of("RATE_LIMIT_EXCEEDED", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) { /* ... */ }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Log the full stack trace WITH the correlation ID and tenant ID,
        // but never leak internals to the caller.
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError.of("INTERNAL_ERROR", "Something went wrong. Reference: " + correlationId()));
    }
}
```

**The multi-tenant angle worth thinking about:** an error message must never leak another tenant's data. "Task 8f3a... not found" is fine; "Task belongs to tenant Globex Corp" is a data leak in an error string. Error responses are part of your isolation boundary.

**Related Spring Boot pieces to build alongside it:**
- A consistent `ApiError` response shape across all services (use a `record`)
- `@Valid` + Bean Validation on request DTOs
- `ProblemDetail` (RFC 7807) — Spring Boot 3's built-in standard error format, worth using instead of a hand-rolled shape
- Returning the correlation ID in every error response so support can trace it

### Dependency injection, beans, and AOP — where they show up here

| Spring concept | Where it appears in this project |
|---|---|
| Constructor injection | Every service class — and you'll feel why, when unit-testing without Spring |
| Bean scopes | Tenant context holder — a singleton service with mutable state is a cross-tenant bug waiting to happen. This project makes the "singleton beans must be stateless" rule concrete rather than abstract. |
| `@PostConstruct` / `@PreDestroy` | Kafka consumer startup, outbox relay shutdown (drain in-flight work) |
| Custom AOP aspect | Write one: an `@Metered` annotation that records per-tenant usage on any method. This is the metering-service feed, and it teaches AOP for real. |
| `@ConditionalOnProperty` | Toggle pooled vs dedicated datasource routing by config |
| `@Async` | Fire-and-forget work — then learn why it's *not* enough for reliable events (that's what the outbox is for) |
| `@Scheduled` | The metering aggregation job — plus the distributed-lock problem it creates when you run two instances |
| `@Configuration` + `@Bean` | Multiple `DataSource` beans for the hybrid tenancy routing |
| Filters vs Interceptors | Tenant context extraction — deciding which layer it belongs in is a real design question |
| Actuator | Health, liveness/readiness probes, metrics endpoints for Kubernetes |
| Profiles (`@Profile`) | `local` / `docker` / `k8s` configuration separation |

### Spring Data JPA concepts

- **N+1 query problem** — load a project with 50 tasks and watch 51 queries fire. Fix with `JOIN FETCH` or `@EntityGraph`. Enable `spring.jpa.show-sql` to actually see it.
- **`@Version`** for optimistic locking, and `@Lock(LockModeType.PESSIMISTIC_WRITE)` for the pessimistic case
- **Lazy vs eager loading**, and `LazyInitializationException` — cause it once by touching a lazy collection outside a transaction
- **Custom queries** — `@Query`, Specifications, and when to drop to `JdbcTemplate` instead (your `SET LOCAL` call is exactly such a case)
- **Auditing** — `@CreatedDate`, `@LastModifiedBy` with an `AuditorAware` that pulls the current user *and tenant* from context

---

## 7c. The Saga Pattern — Given Its Own Home

The doc mentioned saga in passing; it deserves a real place, because it's one of the most-asked distributed-systems questions and this project has a genuinely fitting use case.

### The use case: tenant provisioning

When a new tenant signs up, several things must happen across services — and any of them can fail:

```
1. tenant-service   → create tenant record
2. auth-service     → create Keycloak realm/group, create the admin user
3. core-api         → seed a default project and sample tasks
4. metering-service → initialize the usage/quota record
5. notification-service → send the welcome email
```

There's no single database here, so there's no single ACID transaction. If step 3 fails after steps 1–2 succeeded, you have a half-provisioned tenant: a Keycloak user who can log in to a broken account. That's exactly the problem saga solves.

### Compensating transactions

Each step gets an "undo":

| Step | Compensating action |
|---|---|
| Create tenant record | Mark tenant `PROVISIONING_FAILED` (not a hard delete — keep the audit trail) |
| Create Keycloak realm + admin user | Delete the realm/user |
| Seed default project | Delete seeded data |
| Initialize quota | Remove the usage record |
| Send welcome email | *Not compensatable* — which is exactly why it must be last |

**That last row is the most valuable lesson in the whole pattern:** you cannot un-send an email. Ordering a saga so that irreversible steps come last is a real design constraint, and saying that in an interview signals you've actually thought about sagas rather than memorized the definition.

### Orchestration vs choreography — build orchestration here

- **Choreography** — each service reacts to the previous service's event. No coordinator. Simple for 2–3 steps, but with 5 steps the flow lives nowhere and is miserable to debug.
- **Orchestration** — a central coordinator tells each service what to do next and tracks state explicitly.

For this use case, build **orchestration**, because provisioning has enough steps that you want the state visible in one place:

```sql
CREATE TABLE saga_instances (
    id              UUID PRIMARY KEY,
    saga_type       VARCHAR(100) NOT NULL,     -- 'TENANT_PROVISIONING'
    tenant_id       UUID NOT NULL,
    current_step    VARCHAR(100) NOT NULL,
    status          VARCHAR(50) NOT NULL,      -- RUNNING | COMPLETED | COMPENSATING | FAILED
    payload         JSONB NOT NULL,
    completed_steps JSONB NOT NULL DEFAULT '[]'::jsonb,  -- what to compensate, in reverse
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

The orchestrator advances the saga one step at a time, recording each success. On failure it flips to `COMPENSATING` and walks `completed_steps` in reverse, running each undo.

### Things you'll learn by building it (and can then explain from experience)

- **Saga steps must be idempotent.** The orchestrator will retry, and a retried "create Keycloak user" must not create two.
- **Compensations must be idempotent too**, and must tolerate compensating something that never actually happened.
- **Eventual consistency is user-visible.** A tenant is briefly in `PROVISIONING` — your API has to model that state honestly rather than pretend provisioning is instant.
- **Saga state must survive a crash.** Persist it (that's the table above), and have the orchestrator resume in-flight sagas on startup. Building this makes the "why not just use `@Transactional` across services?" answer obvious rather than theoretical.
- **Saga + Outbox together.** The orchestrator's step commands should themselves be published via the outbox, or you're back to the lost-message problem one level up. Outbox guarantees the event is reliably published; saga coordinates the downstream steps with compensating actions if any one fails — they're complementary, not alternatives.

### Where this fits in the build order

Add it as **Phase 3b**, right after the outbox and idempotency work. That ordering matters: by then you'll already have felt why reliable publishing and idempotent consumption are prerequisites, so the saga has something solid to sit on.

---

## 8. The Interview Answers This Project Buys You

After building this, these questions stop being theoretical:

| Question | Your answer comes from |
|---|---|
| "How do you isolate tenants?" | Phase 1 — including the leak you caused and how RLS stopped it |
| "How do you handle a DB write + message publish atomically?" | Phase 3 — the event you lost, then the outbox that fixed it |
| "How do you prevent duplicate processing?" | Phase 3 — the duplicate notification you caused |
| "How do you rate limit per customer?" | Phase 4 — Redis token bucket at the gateway |
| "How do you debug a latency spike?" | Phase 5 — the investigation you actually ran |
| "How do you handle a noisy neighbour?" | Phase 4 — the load test you ran |
| "How would you migrate a customer to a dedicated database?" | Phase 7 — you did it |
| "Sync vs async — when do you use which?" | Phase 3 — you built both paths |
| "How do you deploy safely?" | Phase 7 — canary pipeline |

---

## 9. Concept Checklist

Tick these off as you implement them:

**Security & Identity**
- [ ] OAuth2/OIDC integration (Keycloak)
- [ ] JWT validation, claims extraction
- [ ] Refresh token rotation with reuse detection
- [ ] JWKS / signing key rotation
- [ ] RBAC with method-level security
- [ ] Tenant context propagation (Scoped Values / ThreadLocal)
- [ ] Zero-trust: every internal call authenticated too

**Data & Persistence**
- [ ] Schema design with tenant-first composite indexes
- [ ] PostgreSQL Row-Level Security policies
- [ ] `SET LOCAL` transaction-scoped tenant context
- [ ] HikariCP tuning; observing `pending` connections
- [ ] `EXPLAIN ANALYZE` on a slow query; fixing it with an index
- [ ] `pg_stat_statements` to find the costliest queries
- [ ] Optimistic locking with `@Version`
- [ ] Pessimistic locking; deliberately causing a lock wait
- [ ] Transaction isolation levels; observing a phantom read
- [ ] Flyway/Liquibase migrations; expand-backfill-contract
- [ ] Read replica for analytics queries

**Distributed Systems**
- [ ] Kafka producer/consumer, partitions, consumer groups, offsets
- [ ] Outbox pattern with `FOR UPDATE SKIP LOCKED`
- [ ] Idempotent consumers with dedup table
- [ ] Dead letter queue
- [ ] CQRS read model
- [ ] Eventual consistency; explaining the window to a user
- [ ] Distributed lock in Redis (for the metering aggregation job)
- [ ] Saga (add a multi-step tenant provisioning workflow)

**Resilience & Scale**
- [ ] Circuit breaker (Resilience4j)
- [ ] Bulkhead isolation
- [ ] Retry with exponential backoff — and understanding why retry *without* a circuit breaker causes retry storms
- [ ] Token bucket rate limiter, per tenant, in Redis
- [ ] Cache-aside with tenant-scoped keys, TTL, invalidation
- [ ] Stateless services behind a load balancer
- [ ] Load test with a noisy neighbour

**Observability**
- [ ] OpenTelemetry distributed tracing end to end
- [ ] Structured JSON logging, correlation ID + tenant ID
- [ ] Micrometer metrics, p95/p99 (not averages)
- [ ] Grafana dashboard, per-tenant breakdown
- [ ] An alert on a leading indicator (pool saturation), not a symptom

**Delivery**
- [ ] Dockerfile per service; docker-compose for the full stack
- [ ] Kubernetes: liveness/readiness probes, resource limits, HPA
- [ ] GitHub Actions: test → build → scan → deploy
- [ ] Canary or blue-green deployment stage
- [ ] Per-tenant feature flags
- [ ] Rollback rehearsal

**Spring Boot core**
- [ ] `@Transactional` propagation: REQUIRED, REQUIRES_NEW, NESTED
- [ ] Transaction isolation levels; observing a non-repeatable read
- [ ] The self-invocation proxy trap — causing it, then fixing it
- [ ] The checked-exception rollback trap; `rollbackFor`
- [ ] Holding a transaction open to observe lock blocking
- [ ] `@RestControllerAdvice` global exception handling
- [ ] `ProblemDetail` (RFC 7807) error responses
- [ ] `@Valid` + Bean Validation on request DTOs
- [ ] Error messages audited for cross-tenant leakage
- [ ] Custom AOP aspect (`@Metered`) feeding the metering service
- [ ] `@Scheduled` job + distributed lock so only one instance runs it
- [ ] Multiple `DataSource` beans for hybrid tenancy routing
- [ ] Actuator health/liveness/readiness endpoints
- [ ] N+1 query problem — causing it, seeing it, fixing it
- [ ] `LazyInitializationException` — causing it once on purpose
- [ ] JPA auditing with tenant-aware `AuditorAware`

**Saga**
- [ ] Orchestrated saga for tenant provisioning
- [ ] Persistent saga state surviving a restart
- [ ] Compensating transactions, executed in reverse
- [ ] Idempotent steps AND idempotent compensations
- [ ] Irreversible step (email) ordered last, deliberately
- [ ] Saga commands published via the outbox

**Modern Java**
- [ ] Virtual Threads; measuring throughput vs platform threads
- [ ] Thread pinning — causing it, then detecting it
- [ ] Structured Concurrency for a fan-out call
- [ ] Scoped Values for tenant context
- [ ] Records for DTOs/events; sealed classes for domain states
- [ ] GraalVM native image for one service; measuring startup/memory

---

## 10. Scope Discipline

The biggest risk to this project is that it becomes too large and you abandon it. Guardrails:

- **Phase 1 alone is already worth doing.** If you stop after the tenancy work, you've still learned the single most valuable thing.
- **Don't build a frontend.** Postman/curl and a few integration tests are enough. The learning is entirely backend.
- **Don't chase feature completeness.** Three task fields is plenty. Nobody is grading the product.
- **Do write down what broke and how you fixed it.** A running `DECISIONS.md` and `INCIDENTS.md` in the repo is the actual artifact — it's what turns this into interview material, and it's what a real architect produces anyway.
- **Timebox each phase to a week.** Move on even if it's imperfect; you can return.
