# Job Framework

A Java/Vert.x job and service orchestrator for OpenShift, built on fabric8.

## What it does

- **Jobs** run to completion. They fire on a cron or time-of-day, optionally
  only after named dependencies have succeeded within the same scheduling
  window. Each firing becomes a Kubernetes `Job`.
- **Services** run continuously as OpenShift `Deployment`s with an optional
  HTTP `Service` and health probes.
- **Codebase layers** let a job or service assemble its runtime classpath
  from multiple images. Each layer becomes an init container that copies
  its `/app/lib/*.jar` into a shared `emptyDir` mounted at
  `/opt/app/classpath`. The main container runs
  `java -cp '/opt/app/classpath/*' <mainClass>`.

## Architecture

```
 POST /jobs      ─┐
 POST /services  ─┼─> ApiVerticle ──> ConfigMap write ─┐
                 ─┘                                    │
                                                       ▼
                                          ┌─── Definition informer ───┐
                                          │ (jobdef-*, svcdef-* CMs)  │
                                          └────────────┬──────────────┘
                                                       │
                                                       ▼
                          scheduler ticks ──> DependencyEngine ──┐
                                                 ▲               │
                                                 │               │ fires
                           JobInformer events ───┘               ▼
                                   ▲                      fabric8 create Job
                                   │                             │
                                   └─── OCP batch/v1 Job ◄───────┘
                                              │
                                              ▼
                                   Pending-run CM store
                                   (persist PENDING_DEPS intent)
```

### Components

- `DependencyEngine` — single-threaded (Vert.x event loop). Registers
  definitions, tracks latest run state, emits "ready to fire" callbacks.
- `DefinitionStore<T>` — generic ConfigMap-backed store (one per definition
  type). Writes go through `upsert()`, reads via an informer that feeds
  the engine on add/update/delete. The same code path handles live
  registrations and cold-start recovery.
- `PendingRunStore` — ConfigMap-backed store for in-flight `PENDING_DEPS`
  intents. Wrote when a run enters PENDING_DEPS; deleted when it transitions
  to SUBMITTED (the Job resource takes over as source of truth).
- `SchedulerVerticle` — ticks once per second, computes next-fire time per
  job using `cron-utils`, calls `engine.scheduleFire(...)`.
- `JobInformer` — watches `Job`s with our management labels; on terminal
  transitions calls `engine.onJobTerminal(...)`. Cold-start replay populates
  terminal state via this informer's initial LIST.
- `ApiVerticle` — thin HTTP façade that writes CMs and does NOT mutate the
  engine directly.

### Persistence model

Three classes of state, three different stores:

| State                           | Where                                      |
|---------------------------------|--------------------------------------------|
| `JobDefinition`/`ServiceDefinition` | `jobdef-<name>` / `svcdef-<name>` ConfigMap |
| In-flight `PENDING_DEPS` run    | `pending-<name>-<runId>` ConfigMap         |
| Terminal run outcome            | The `Job` resource itself                  |

The orchestrator holds no authoritative state in memory. On restart:
1. Definition informers sync → engine is repopulated.
2. Job informer's initial LIST → terminal state replayed.
3. Pending-run CMs listed → `rehydratePending` invoked for each; runs with
   since-completed dependencies fire immediately.

## Layout

```
framework/          the orchestrator process (deploy this once)
examples/
  shared-lib/       a library jar used by both other examples
  example-job/      a run-to-completion workload
  example-service/  an always-on HTTP workload
deploy/             RBAC + orchestrator Deployment + example submit script
```

## Building

```sh
./gradlew :framework:installDist
./gradlew :examples:example-service:installDist
./gradlew :examples:example-job:jar
./gradlew :examples:shared-lib:jar
./gradlew :framework:test
```

Then build one image per module using the provided `Dockerfile`s. Push to
the OCP internal registry (or your registry of choice) and tag as expected
by `deploy/example-usage.sh`.

## Deploying

```sh
oc new-project orchestrator-system
oc apply -f deploy/rbac.yaml
oc apply -f deploy/orchestrator.yaml
bash deploy/example-usage.sh
```

## HTTP API

```
POST   /jobs              register/replace a JobDefinition (writes jobdef-<name> CM)
POST   /services          register/replace a ServiceDefinition (writes svcdef-<name> CM)
DELETE /jobs/:name        delete the CM (informer cascades removal to engine)
DELETE /services/:name    delete the CM + tear down Deployment/Service
POST   /jobs/:name/run    manually trigger a firing
GET    /jobs              list registered jobs (from engine)
GET    /runs              current DAG run state
GET    /health            liveness
```

Registration is **asynchronous**: POST returns 201 when the CM is written,
but the engine observes it via informer callback a moment later. For
read-your-writes guarantees, GET after POST.

## Namespace model

The orchestrator is namespaced. A single orchestrator manages Jobs,
Deployments, Services, and ConfigMaps in its own namespace only; incoming
definitions must specify `namespace` equal to the orchestrator's, or they
are rejected with an error in the logs. For multi-namespace use, run one
orchestrator per namespace. (A ClusterRole-backed variant is possible but
not implemented here.)

## What this skeleton does not yet do

- **Leader election.** Run `replicas: 1`. Two replicas would each try to
  submit Jobs on each scheduler tick. Fix with fabric8's `LeaderElector`
  once you need HA.
- **Full CronJob semantics** — concurrency policy is hard-coded to `Forbid`
  (skip firing if a prior run is still live); starting-deadline is not
  enforced beyond the scheduling window on dependencies.
- **Webhook/event-driven triggers** — `Schedule.Manual` + `POST /jobs/:name/run`
  is the only non-clock trigger. An `OnUpstreamSuccess` trigger type would
  be a small additional change.
