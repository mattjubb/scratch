# Version Dashboard API

JAX-RS REST backend for the [Version Stage Dashboard](../version-dashboard).  
Built with **Quarkus 3**, **RESTEasy + Jackson**, and **SmallRye OpenAPI**.

---

## Quick start

```bash
# requires Java 17–23 (see note below)
cd version-dashboard-api
./gradlew quarkusDev     # hot-reload dev server on http://localhost:8080
```

| URL | What |
|-----|------|
| `http://localhost:8080/q/swagger-ui` | Interactive Swagger UI |
| `http://localhost:8080/q/openapi`    | Raw OpenAPI 3 YAML     |
| `http://localhost:8080/api/projects` | API root               |

Run tests:
```bash
./gradlew test
```

Build a fast-JAR:
```bash
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```

> **Java version note:** Gradle 8.x requires Java ≤ 23. If Java 25 is your default
> `JAVA_HOME`, `gradle.properties` overrides it with the path to your Java 23 installation.
> Adjust `org.gradle.java.home` if your JDK 23 lives elsewhere, or remove it once Quarkus
> ships a Gradle 9-compatible plugin.

---

## Data model

Three seed projects are loaded from `src/main/resources/projects.json` at startup
and held in an in-memory store (a CDI `@ApplicationScoped` bean).

Each **Project** owns:

| Field | Type | Notes |
|-------|------|-------|
| `id` | string | dot-separated, e.g. `core.datapedia` |
| `name` | string | display name |
| `description` | string | free text |
| `githubRepo` | string | URL |
| `imageTag` | string | Docker image name prefix |
| `leadDevelopers` | `LeadDeveloper[]` | name + email |
| `artifacts` | `Artifact[]` | name + URL |
| `dependencies` | `string[]` | ids of upstream projects |
| `stages` | `Map<StageKey, Stage>` | 9 entries |

A **Stage** (`StageKey` = `{lane}-{iteration}`, lanes: `snapshot/candidate/release`, iterations: `previous/current/next`) holds:

| Field | Type |
|-------|------|
| `version` | string (semver) |
| `imageVersion` | string |
| `deps` | `Map<depId, DepPin>` |
| `prs` | `PullRequest[]` |
| `testsPassed` / `testsTotal` | int |
| `lastUpdated` | ISO-8601 string |
| `lastUpdatedBy` | string |

---

## API reference

### Projects

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/projects`               | List all (optional `?q=` filter) |
| `POST`   | `/api/projects`               | Create project |
| `GET`    | `/api/projects/{id}`          | Get project |
| `PATCH`  | `/api/projects/{id}`          | Update metadata |
| `DELETE` | `/api/projects/{id}`          | Delete (cascades dep cleanup) |
| `POST`   | `/api/projects/reset`         | ⚠ Reset to seed data |

### Lead developers & Artifacts

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/projects/{id}/lead-developers` | Get list |
| `PUT`  | `/api/projects/{id}/lead-developers` | Replace list |
| `GET`  | `/api/projects/{id}/artifacts`       | Get list |
| `PUT`  | `/api/projects/{id}/artifacts`       | Replace list |

### Dependencies

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/projects/{id}/dependencies`         | List dep ids |
| `POST`   | `/api/projects/{id}/dependencies`         | Add dep (seeds all 9 dep-pin entries) |
| `DELETE` | `/api/projects/{id}/dependencies/{depId}` | Remove dep + all dep-pin entries |

### Stages

| Method | Path | Description |
|--------|------|-------------|
| `GET`   | `/api/projects/{id}/stages`                         | All 9 stages |
| `GET`   | `/api/projects/{id}/stages/{stageKey}`              | One stage |
| `PATCH` | `/api/projects/{id}/stages/{stageKey}`              | Patch fields |
| `POST`  | `/api/projects/{id}/stages/{stageKey}/promote`      | Promote (optional `?actor=`) |
| `POST`  | `/api/projects/{id}/stages/{stageKey}/rebase`       | Rebase all dep pins |
| `POST`  | `/api/projects/{id}/stages/rebase-all`              | Rebase everything |

### Dep pins

| Method | Path | Description |
|--------|------|-------------|
| `GET`   | `/api/projects/{id}/stages/{sk}/deps`              | All dep pins |
| `PATCH` | `/api/projects/{id}/stages/{sk}/deps/{depId}`      | Update one pin |
| `POST`  | `/api/projects/{id}/stages/{sk}/deps/{depId}/rebase` | Rebase one cell |
| `POST`  | `/api/projects/{id}/stages/deps/{depId}/rebase`    | Rebase dep across all stages |

### Drift

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/projects/{id}/stages/{sk}/drift` | Drift info per dep at a stage |

### PRs

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/projects/{id}/stages/{sk}/prs`           | List PRs |
| `POST`   | `/api/projects/{id}/stages/{sk}/prs`           | Add PR |
| `DELETE` | `/api/projects/{id}/stages/{sk}/prs/{number}`  | Remove PR |

---

## Promotion rules

Mirrors the frontend `promote()` logic exactly:

```
*-next     → *-current       (old current → previous; dep pins rebased)
*-current  → nextLane-next   (snapshot-current → candidate-next, etc.)
release-current              terminal — cannot be promoted
*-previous                   read-only — cannot be promoted
```

Only `release-next` is labelled **Release** in the UI; the API uses the same
endpoint regardless.

---

## CORS

CORS is enabled for all origins in `application.properties` for local development.
Tighten `quarkus.http.cors.origins` before deploying to production.
