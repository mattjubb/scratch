# End-to-end example usage.
#
# Assumes:
#   - the orchestrator is running and reachable at $ORCH
#   - the three codebase-layer images have been pushed to the OCP registry:
#       orchestrator-system/shared-lib:0.1.0
#       orchestrator-system/example-job:0.1.0
#       orchestrator-system/example-service:0.1.0

ORCH=http://orchestrator.orchestrator-system.svc

# ---------------------------------------------------------------------------
# 1. Register the example service. The orchestrator will build and apply a
#    Deployment + Service. Init containers stage shared-lib then example-service
#    jars into /opt/app/classpath, and the main container runs
#    `java -cp '/opt/app/classpath/*' io.acme.example.service.GreeterService`.
# ---------------------------------------------------------------------------

curl -X POST $ORCH/services \
  -H 'content-type: application/json' \
  -d '{
    "name": "greeter",
    "namespace": "orchestrator-system",
    "replicas": 2,
    "httpPort": 8080,
    "runtime": {
      "runtimeImage": "registry.access.redhat.com/ubi9/openjdk-21-runtime:latest",
      "mainClass": "io.acme.example.service.GreeterService",
      "codebase": [
        { "name": "shared-lib",      "image": "image-registry.openshift-image-registry.svc:5000/orchestrator-system/shared-lib:0.1.0" },
        { "name": "example-service", "image": "image-registry.openshift-image-registry.svc:5000/orchestrator-system/example-service:0.1.0" }
      ],
      "env": { "PORT": "8080" },
      "resources": {
        "cpuRequest": "100m", "cpuLimit": "500m",
        "memRequest": "256Mi", "memLimit": "512Mi"
      }
    }
  }'

# ---------------------------------------------------------------------------
# 2. Register an upstream job that runs every day at 02:00 UTC.
# ---------------------------------------------------------------------------

curl -X POST $ORCH/jobs \
  -H 'content-type: application/json' \
  -d '{
    "name": "daily-extract",
    "namespace": "orchestrator-system",
    "schedule": { "type": "timeOfDay", "at": "02:00:00", "zone": "UTC" },
    "runtime": {
      "runtimeImage": "registry.access.redhat.com/ubi9/openjdk-21-runtime:latest",
      "mainClass": "io.acme.example.job.ReportJob",
      "codebase": [
        { "name": "shared-lib",  "image": "image-registry.openshift-image-registry.svc:5000/orchestrator-system/shared-lib:0.1.0" },
        { "name": "example-job", "image": "image-registry.openshift-image-registry.svc:5000/orchestrator-system/example-job:0.1.0" }
      ],
      "env": { "DATASET": "treasury-eod" }
    },
    "activeDeadline": "PT10M"
  }'

# ---------------------------------------------------------------------------
# 3. Register a downstream job that waits for daily-extract to succeed and
#    then runs. Its schedule is "manual" — it never fires on a clock; it
#    fires purely because the DependencyEngine sees its upstream succeed
#    AFTER a scheduleFire call. To wire that, we use `timeOfDay` slightly
#    later than the parent, so the per-day scheduling window lines up.
# ---------------------------------------------------------------------------

curl -X POST $ORCH/jobs \
  -H 'content-type: application/json' \
  -d '{
    "name": "daily-report",
    "namespace": "orchestrator-system",
    "schedule": { "type": "timeOfDay", "at": "02:05:00", "zone": "UTC" },
    "dependencies": ["daily-extract"],
    "runtime": {
      "runtimeImage": "registry.access.redhat.com/ubi9/openjdk-21-runtime:latest",
      "mainClass": "io.acme.example.job.ReportJob",
      "codebase": [
        { "name": "shared-lib",  "image": "image-registry.openshift-image-registry.svc:5000/orchestrator-system/shared-lib:0.1.0" },
        { "name": "example-job", "image": "image-registry.openshift-image-registry.svc:5000/orchestrator-system/example-job:0.1.0" }
      ],
      "env": { "DATASET": "treasury-eod-report" }
    }
  }'

# ---------------------------------------------------------------------------
# 4. Inspect state.
# ---------------------------------------------------------------------------
curl $ORCH/jobs
curl $ORCH/runs

# Or force an immediate firing:
curl -X POST $ORCH/jobs/daily-extract/run
