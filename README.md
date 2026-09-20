# TeamPulse

A multi-tenant B2B SaaS project-and-task management platform (a focused Jira/Asana), built as a hands-on learning project rather than a product.

**This is not a portfolio toy.** The goal is to personally hit — and fix — the failure modes that senior/lead/architect-level Java backend interviews are really about: cross-tenant data leaks, lost events, duplicate processing, transactions that won't roll back. Every deliberate bug and its fix is logged in [`INCIDENTS.md`](./INCIDENTS.md); every architectural choice and its alternatives are logged in [`DECISIONS.md`](./DECISIONS.md). Those two files are the actual artifact of this project.

## Why multi-tenant SaaS

Multi-tenancy is an architectural decision, not a feature. The API gateway, application logic, database queries, and background jobs all have to be tenant-aware from day one — a "decide once and live with it" call that forces the same kind of cross-cutting thinking an architect is expected to bring, more than a narrower project (ticket booking, URL shortener, payments) would.

## Tenant isolation model

Built as the **Pool model** — shared database, shared schema, every tenant-owned table carries a `tenant_id` column — enforced with **PostgreSQL Row-Level Security** rather than application-level `WHERE` clauses. RLS makes the database itself refuse cross-tenant reads even if the application code has a bug, which is the whole point: a missing `WHERE` clause becomes a non-event instead of a data breach.

The plan is to later add a documented path to a **Silo model** (database-per-tenant) for enterprise/regulated tenants, routed via tenant metadata — a hybrid approach that mirrors what most mature SaaS products actually run.

## Tech stack

| Layer | Choice |
|---|---|
| Language | Java 21 LTS |
| Framework | Spring Boot 3.x |
| Build | Maven |
| Primary DB | PostgreSQL 16 |
| Migrations | Flyway |
| Cache / locks | Redis *(added in Phase 4)* |
| Message broker | Apache Kafka *(added in Phase 3)* |
| Identity | Keycloak (OAuth2/OIDC) *(added in Phase 2)* |
| Resilience | Resilience4j *(added in Phase 4)* |
| Observability | OpenTelemetry, Micrometer/Prometheus, Grafana *(added in Phase 5)* |
| Local infra | Docker Compose |

Infrastructure is added to `docker-compose.yml` only in the phase that first needs it, not all up front — see [`DECISIONS.md`](./DECISIONS.md).

## Services (eventual)

Only `core-api` exists today. The full breakdown, built incrementally:

- **`tenant-service`** — tenant registration, tier/isolation metadata, provisioning, tenant routing
- **`auth-service`** — Keycloak integration, JWT issuance, refresh token rotation, RBAC
- **`core-api`** *(in progress)* — projects/tasks/comments CRUD, tenant context propagation, RLS, optimistic locking, outbox writes
- **`outbox-relay`** — polls the outbox table, publishes to Kafka, at-least-once delivery
- **`notification-service`** — Kafka consumer, idempotent processing, circuit breaker, DLQ
- **`analytics-service`** — CQRS read side, denormalized dashboards
- **`metering-service`** — per-tenant usage tracking, quota enforcement
- **`api-gateway`** — routing, edge JWT validation, per-tenant rate limiting

## Build order

Built in phases, deliberately sequenced so each failure mode is caused and felt before the pattern that fixes it is applied:

1. **Monolith with tenancy** *(current phase)* — CRUD → naive `tenant_id` filtering → cause a cross-tenant leak → fix with RLS → cause a connection-pool context leak (`SET` vs `SET LOCAL`) → automated isolation tests in CI
2. **Auth & authorization** — Keycloak, JWT, RBAC, refresh token rotation
3. **Async & events** — Kafka, lose an event on crash, fix with the outbox pattern, cause a duplicate notification, fix with idempotent consumers
4. **Scale & resilience** — per-tenant rate limiting, cache-aside, circuit breakers, noisy-neighbour load testing
5. **Observability** — distributed tracing, structured logging, per-tenant metrics
6. **CQRS & metering** — analytics read model, usage aggregation, quota enforcement
7. **Hybrid tenancy & delivery** — migrate a tenant from pooled to dedicated, Kubernetes, CI/CD with canary

See the full brief for detail on each phase.

## Running locally

```bash
# start Postgres
docker-compose up -d

# run the app
mvn spring-boot:run
```

The app validates its schema against Flyway migrations (`ddl-auto: validate`) rather than auto-generating tables — every entity requires a hand-written migration under `src/main/resources/db/migration`.

## Project layout

```
src/main/java/com/teampulse/coreapi/   application code
src/main/resources/application.yml     config
src/main/resources/db/migration/       Flyway migrations
DECISIONS.md                           architectural decisions + alternatives + why
INCIDENTS.md                           every bug caused, how it was diagnosed, the fix
```
