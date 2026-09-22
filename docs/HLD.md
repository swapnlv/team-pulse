# TeamPulse — High-Level Design (HLD)

## Purpose & core architectural bet

TeamPulse is a B2B SaaS project/task manager (a scoped-down Jira/Asana) whose real purpose is to force every architectural decision a multi-tenant system requires — isolation, async reliability, resilience, observability — and to log each deliberately-caused bug and fix as the actual learning artifact (`INCIDENTS.md`, `DECISIONS.md`).

**The central bet:** tenant isolation is enforced at the database layer via **PostgreSQL Row-Level Security**, not application `WHERE` clauses — so a missing filter in app code degrades to "no rows returned," never a cross-tenant leak.

## Target system topology (end state, built incrementally)

```
                                   ┌─────────────┐
                                   │  api-gateway │  routing, edge JWT validation,
                                   │ (Spring Cloud│  per-tenant rate limiting (Redis)
                                   │   Gateway)   │
                                   └──────┬──────┘
                    ┌────────────┬────────┼────────┬─────────────┐
                    ▼            ▼        ▼         ▼             ▼
            ┌──────────────┐ ┌────────┐ ┌───────────┐ ┌──────────────┐ ┌─────────────┐
            │tenant-service│ │  auth- │ │  core-api  │ │  metering-   │ │ analytics-  │
            │ provisioning,│ │ service│ │ projects/  │ │  service     │ │ service     │
            │ tenant meta, │ │(Keycloak│ │ tasks CRUD,│ │ usage/quota  │ │ (CQRS read  │
            │ saga orch.   │ │ OIDC,   │ │ RLS, outbox│ │              │ │ model)      │
            └──────────────┘ │ JWT,    │ └─────┬──────┘ └──────┬───────┘ └──────▲──────┘
                              │ RBAC)   │       │ writes outbox │ reads usage    │ consumes
                              └────────┘       ▼               ▲                │ events
                                        ┌──────────────┐       │                │
                                        │ outbox-relay │───────┴────────────────┘
                                        │ FOR UPDATE   │  publishes to
                                        │ SKIP LOCKED  │
                                        └──────┬───────┘
                                               ▼
                                        ┌──────────────┐
                                        │    Kafka     │
                                        └──────┬───────┘
                                               ▼
                                        ┌────────────────────┐
                                        │ notification-service│ idempotent consumer,
                                        │ circuit breaker, DLQ │ email/in-app notify
                                        └────────────────────┘

Shared infra: PostgreSQL 16 (pooled, RLS) ── Redis (cache, rate-limit, locks) ── Keycloak (OIDC)
Cross-cutting: OpenTelemetry tracing, Micrometer→Prometheus→Grafana, structured JSON logs (correlation ID + tenant ID)
```

Only **`core-api`** exists so far; every other box is future work per the phased build order in `multi-tenant-saas-project.md` §7.

## Tenant isolation model

- **Pool model**: one shared schema, every tenant-owned table carries `tenant_id`, enforced by Postgres RLS policies (`USING (tenant_id = current_setting('app.current_tenant_id')::UUID)`).
- Request-scoped tenant context is set via `SET LOCAL` inside the transaction (not plain `SET`, which would leak across pooled HikariCP connections into the next request — this exact bug is planned to be caused and fixed in Phase 1 step 5).
- **Hybrid path (later)**: a documented migration to a Silo model (dedicated DB) for enterprise/regulated tenants, tenant-routed via `datasource_ref` metadata already present on the `tenants` table.

## Reliability patterns designed into the core

- **Outbox pattern**: business row + outbox row written in one transaction in `core-api`; `outbox-relay` polls with `FOR UPDATE SKIP LOCKED` and publishes to Kafka — guarantees no event is lost on a crash between commit and publish.
- **Idempotent consumers**: `notification-service` dedups on event ID via a unique-constraint table, tolerating Kafka's at-least-once redelivery.
- **CQRS**: `analytics-service` builds a denormalized read model off the same event stream so dashboard reads never contend with live write traffic.
- **Saga (orchestrated)**: tenant provisioning spans `tenant-service` → `auth-service` → `core-api` → `metering-service` → `notification-service`; a persisted `saga_instances` table drives compensating actions in reverse on failure, with the irreversible step (welcome email) deliberately ordered last.

## Current implementation status — Phase 1, Step 1 (done)

`core-api` only, no tenancy, no auth:
- **Schema** (`V1__init_schema.sql`): `tenants` (unreferenced scaffold row), `users` (seeded, no REST layer), `projects`, `tasks` (FK to project + nullable assignee, `@Version` optimistic lock).
- **REST**: full CRUD on `/api/projects` and `/api/tasks`, `@Valid` input checks, `@RestControllerAdvice` → 404/400.
- **Persistence**: Spring Data JPA + Flyway (`ddl-auto: validate` — schema is always hand-written, never Hibernate-generated).
- **Tests**: `ProjectTaskFlowTests` (Testcontainers Postgres + MockMvc), golden-path verified manually via curl against the app + docker-compose Postgres (Testcontainers itself is currently blocked locally by a Docker Desktop named-pipe incompatibility on this machine, unrelated to the code).

See `docs/LLD.md` for the class-level, endpoint-level design of what's actually built.

## What Phase 1 Steps 2–6 add next (not yet built)

`tenant_id` columns on the existing tables → naive app-level `WHERE tenant_id = ?` filtering → a **deliberately caused** cross-tenant leak → RLS policies fixing it → a **deliberately caused** `SET`-vs-`SET LOCAL` connection-pool leak → automated cross-tenant isolation tests wired into CI. That's the next implementation plan.
