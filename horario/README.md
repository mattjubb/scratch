# Vasara Compute Framework

Java-based compute orchestration on **Temporal.io** for **OpenShift**, fronted by the
Vasara Compute Monitor UI (`compute-controlplane/src/main/resources/static/index.html`).

Three compute types — **Services**, **Jobs**, **Tasks** — each defined by a YAML file
whose directory path becomes its identifier (`/trade/booking/booker.yaml` →
`/trade/booking/booker`). Code versions are resolved per `(group, project, lane)` via an
**ImageVersionAPI**; images run as **init containers** that unpack Spring Boot
`BOOT-INF/` contents onto a shared `emptyDir`; the main container launches
`java -cp '/ext/classes:/ext/lib/*' <mainClass>`.

## Modules

| Module | Purpose |
|---|---|
| `compute-model` | Records for definitions, identifiers, states |
| `compute-yaml` | Walks `deploy/definitions/...`, derives IDs from paths, parses YAML |
| `compute-image` | HTTP client for ImageVersionAPI |
| `compute-ocp` | Fabric8-based OCP helpers (init-container compose, Deployment/Job apply, log stream) |
| `compute-temporal` | Workflow / activity interfaces & impls; namespace resolver; worker factory |
| `compute-controlplane` | Vert.x backend — REST + SSE log stream + Vasara UI; embeds Temporal workers |
| `compute-subtask-worker` | Image entrypoint for ephemeral task workers |

## Lane / Temporal-namespace model

- All OCP resources land in **one** OCP namespace (`vasara` by default), labeled with
  `group / project / lane`.
- Each **`(group, project, lane)`** triple → its own Temporal namespace, e.g.
  `rates-swaps-dev`. The `SchedulerWorkflow` runs in a `system` Temporal namespace.

## Build

```bash
./gradlew build
```

Requires JDK 21+. Tests use JUnit 5 + Mockito.

## Run locally (dry-run)

1. Start Temporal dev server:
   ```bash
   temporal server start-dev
   ```
2. Launch control plane (no OCP cluster needed):
   ```bash
   export COMPUTE_DRY_RUN=true
   export COMPUTE_DEFINITIONS_DIR=deploy/definitions
   export TEMPORAL_TARGET=127.0.0.1:7233
   java -jar compute-controlplane/build/libs/compute-controlplane-1.0.0-SNAPSHOT-all.jar
   ```
3. Open <http://localhost:8080>.

## REST API

| Method | Path | Purpose |
|---|---|---|
| GET  | `/api/lanes` | List lanes |
| GET  | `/api/services?lane=` | List services (Temporal fan-out) |
| POST | `/api/services/{path}/{action}` | start/stop/restart/ice signal |
| GET  | `/api/jobs?lane=&date=` | List jobs for a day |
| POST | `/api/jobs/{path}/manual-run` | Force run with args |
| GET  | `/api/tasks?lane=` | List parent tasks |
| POST | `/api/tasks` | Submit a task |
| GET  | `/api/tasks/{id}` | Parent + subtasks |
| GET  | `/api/logs/{kind}/{id}` (SSE) | Stream pod logs |
| GET  | `/` | Serve UI |

## Layout of `deploy/definitions/`

```
deploy/definitions/
  services/
    rates/swaps/pricer.yaml      → /rates/swaps/pricer
  jobs/
    rates/eod/curve-build.yaml   → /rates/eod/curve-build
    rates/eod/market-data.yaml   → /rates/eod/market-data
```
