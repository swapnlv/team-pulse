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
