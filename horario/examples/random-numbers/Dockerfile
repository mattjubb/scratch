# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Copy the whole project so Gradle can resolve inter-module deps.
# In a real CI pipeline you would copy only the pre-built fat jar artifact.
COPY . .
RUN ./gradlew :examples:random-numbers:workerJar --no-daemon -q

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="random-numbers-worker"
LABEL org.opencontainers.image.description="Vasara Compute subtask worker: random number generation example"

WORKDIR /app

# The fat jar includes WorkerMain (from compute-subtask-worker) and
# RandomNumberActivities (registered via SPI) with all transitive deps.
COPY --from=build /build/examples/random-numbers/build/libs/random-numbers-worker.jar /app/worker.jar

# WorkerMain reads these env vars (injected by the control plane into the OCP Job):
#   TASK_ID              parent task UUID
#   TASK_QUEUE           Temporal task queue  (task-{taskId})
#   TEMPORAL_TARGET      Temporal host:port
#   TEMPORAL_NAMESPACE   Temporal namespace
#   WORKER_IDLE_MILLIS   idle timeout ms      (default 300 000)
#   WORKER_MAX_LIFETIME_MILLIS               (default 21 600 000 = 6 h)

ENTRYPOINT ["java", "-jar", "/app/worker.jar"]
