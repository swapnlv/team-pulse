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

## 2026-09-21 — Entity IDs generated in the application, not the database

**Alternatives considered:** `DEFAULT gen_random_uuid()` on each `id` column (requires the `pgcrypto` extension)

**Why:** Avoids adding a Postgres extension for no benefit yet. `UUID.randomUUID()` assigned when the entity is constructed is simpler to reason about and identical from the application's perspective.

---

## 2026-09-21 — Testcontainers added in Phase 1 step 1, ahead of when it's strictly needed

**Alternatives considered:** H2 in-memory for now, add Testcontainers later when RLS testing needs real Postgres

**Why:** RLS (step 4) is Postgres-specific and can't be tested against H2 at all, so Testcontainers is coming in regardless — bringing it in now means the pom and test setup only change once, and the CRUD integration test already runs against the real engine the app targets in production.

---
