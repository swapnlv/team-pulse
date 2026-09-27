# Incidents

Every bug caused on purpose (and the ones caused by accident), how it was diagnosed, and the fix. This is the real artifact of the project — each entry should be detailed enough to retell as an interview story.

Template per entry:

```
## <date> — <short title>

**What broke:** ...

**How I noticed / reproduced it:** ...

**Root cause:** ...

**The fix:** ...

**What I'd tell an interviewer:** 3-4 sentences, as if explaining it out loud.
```

---

## 2026-09-26 — N+1 queries when listing projects with their tasks

**What broke:** `ProjectRepository.findAll()` followed by reading `project.getTasks()` for each project cost **1 + N** SQL statements. With 3 projects (6 tasks) that was 4 statements; the same code with 50 projects would run 51, and with 5,000 it would run 5,001. Nothing failed and the results were correct, which is why this kind of bug survives code review.

**How I noticed / reproduced it:** I wrote `ProjectTaskPersistenceTest` against a throwaway Postgres 16 container (Testcontainers) with Hibernate's `generate_statistics` on, so the test could *count* prepared JDBC statements instead of me eyeballing `show-sql` output. Test `q3b` saved 3 projects with 2 tasks each, cleared the persistence context, called `findAll()`, then touched every `getTasks()`:
`findAll()` = 1 statement, touching the collections = 3 more, total 4.

**Root cause:** `Project.tasks` is a `@OneToMany` and is lazy by default. `findAll()` loads only the `projects` rows; the first `getTasks()` call on each project fires its own `SELECT ... FROM tasks WHERE project_id = ?`. The database did exactly what it was asked. The problem is in the application layer: a lazy association touched inside a loop.

**The fix:** `@EntityGraph(attributePaths = {"tasks"})` on `ProjectRepository.findAll()`, so Hibernate loads projects and tasks in one joined query. After the fix: `findAll()` = 1 statement, touching every collection = 0 more, total 1. The test now asserts these numbers, so removing the graph makes it fail instead of silently regressing. Alternatives I did not take: `JOIN FETCH` in a `@Query` (same mechanism, explicit JPQL) and batch fetching (`@BatchSize` / `hibernate.default_batch_fetch_size`), which turns N queries into N/batchSize and also works with pagination. Caveat of the chosen fix: a collection fetch join cannot be combined with database-level pagination, so a paged project list would need batch fetching instead.

**What I'd tell an interviewer:** N+1 is when you load a list of parents with one query and then, because a lazy association is touched in a loop, the ORM fires one extra query per parent. It is an application-layer problem, not a database one, and it hides because it looks fine with three rows in development and only hurts when data grows. I caught it by counting SQL statements with Hibernate statistics in a test: 3 projects cost 4 queries. I fixed it with an entity graph so projects and tasks load in one query, and I pinned the count with an assertion so it cannot silently come back.

---
## 2026-09-27 — Testcontainers could not find Docker, while the Docker CLI worked fine

**What broke:** The first persistence test never reached Postgres. Every run died in `@BeforeAll` with `IllegalStateException: Could not find a valid Docker environment`, even though `docker ps` and `docker version` worked in the same shell and the compose Postgres was up.

**How I noticed / reproduced it:** `mvn test -Dtest=ProjectTaskPersistenceTest`, 100% reproducible. The useful detail was not in the stack trace but in the captured `system-out` of the surefire XML report, which lists what Testcontainers actually attempted:

```
NpipeSocketClientProviderStrategy: failed with exception BadRequestException (Status 400: {"ID":"","Containers":0,...,"Labels":["com.docker.desktop.address=npipe://\.\pipe\docker_cli"],...})
```

So the pipe answered — with an all-zero `Info` payload and HTTP 400.

**Root cause:** Not what the first two theories said, and both wrong turns are worth keeping:

1. *"It's the wrong named pipe."* The machine has two Docker contexts — `default` on `npipe:////./pipe/docker_engine` and the active `desktop-linux` on `npipe:////./pipe/dockerDesktopLinuxEngine`. Setting `DOCKER_HOST` to the active one changed the strategy name in the log to `EnvironmentAndSystemPropertyClientProviderStrategy` and produced the *identical* 400, so the pipe was never the problem. Probing all four pipe × API-version combinations with the plain CLI succeeded every time, which ruled this out for good.
2. *"It's the Docker API version floor."* Engine 29 reports `API version: 1.52 (minimum version 1.44)`, and `DOCKER_API_VERSION=1.32 docker info` reproduces the exact same opaque 400 through the CLI — a very convincing false positive. But bumping Testcontainers from 1.19.8 to 1.21.3 (docker-java 3.3.x → 3.4.2) still failed, and 1.21.4 works while *also* shipping docker-java 3.4.2. Same client library, different result, so the API version was not the cause.

The actual cause is in Testcontainers' own Docker-environment discovery, fixed somewhere between 1.21.3 and 1.21.4. Spring Boot 3.3.4's BOM pins 1.19.8, which is far too old for Docker Desktop 4.53.

**The fix:** Override the managed version in the pom — `<testcontainers.version>1.21.4</testcontainers.version>`. No `DOCKER_HOST`, no `~/.testcontainers.properties`, no daemon config.

**What I'd tell an interviewer:** Testcontainers failed to find Docker on a machine where the Docker CLI worked perfectly, which immediately suggests a client-library problem rather than a daemon or configuration problem. The stack trace was useless — the diagnostic was in the surefire report's captured stdout, where Testcontainers logs every connection strategy it tried and why each failed. That told me the named pipe was responding with a stub `Info` and an HTTP 400. I chased two plausible wrong theories: the wrong Docker context, which I killed by reproducing success across all four pipe and API-version combinations with the CLI, and the Engine 29 minimum API version of 1.44, which was seductive because forcing an old API version through the CLI reproduced the identical opaque 400. What actually settled it was noticing that the version that works and the version that fails ship the *same* docker-java, so the bug had to be in Testcontainers' discovery code, not the transport. The real lesson is that Spring Boot's BOM pins a Testcontainers version that ages badly against Docker Desktop, and that a dependency's managed version is a thing you should be willing to override.

---
