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
