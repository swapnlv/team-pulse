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
