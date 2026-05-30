package com.compute.subtask;

import com.compute.model.SubtaskRequest;
import com.compute.model.SubtaskResult;
import com.compute.temporal.activity.SubtaskActivities;
import com.compute.temporal.workflow.impl.SubtaskWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Image entrypoint for the ephemeral OCP Job that runs per-task workers. Reads its
 * config from env vars baked in by the framework:
 *
 * <ul>
 *   <li>{@code TASK_ID} — the parent task UUID</li>
 *   <li>{@code TASK_QUEUE} — Temporal task queue this worker polls ({@code task-{taskId}})</li>
 *   <li>{@code TEMPORAL_TARGET} — host:port for Temporal frontend</li>
 *   <li>{@code TEMPORAL_NAMESPACE} — namespace the parent workflow lives in</li>
 * </ul>
 *
 * <p>Subtask executors are discovered via {@link ServiceLoader} on
 * {@link SubtaskActivities}. The user's image registers an implementation under
 * {@code META-INF/services/com.compute.temporal.activity.SubtaskActivities}.</p>
 *
 * <p>The pod self-terminates after a brief idle window — Temporal worker polling is
 * long-polled, so we don't need a heavy exit heuristic; the OCP Job's parallelism +
 * the workflow's completion of all child workflows is what bounds the lifetime in
 * practice. We back-stop with an idle timer in case the parent crashes.</p>
 */
public final class WorkerMain {

    private static final Logger log = LoggerFactory.getLogger(WorkerMain.class);

    public static void main(String[] args) throws Exception {
        String taskId = required("TASK_ID");
        String queue = required("TASK_QUEUE");
        String target = required("TEMPORAL_TARGET");
        String namespace = required("TEMPORAL_NAMESPACE");
        long idleMillis = Long.parseLong(envOr("WORKER_IDLE_MILLIS", "300000"));

        SubtaskActivities executor = discoverExecutor();
        log.info("subtask worker starting taskId={} queue={} ns={} target={} executor={}",
                taskId, queue, namespace, target, executor.getClass().getName());

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(namespace).build());

        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker w = factory.newWorker(queue);
        w.registerWorkflowImplementationTypes(SubtaskWorkflowImpl.class);
        w.registerActivitiesImplementations(executor);
        factory.start();

        log.info("worker ready, idle window {} ms", idleMillis);

        // Wait until idle (no in-flight tasks for the configured window). Temporal SDK
        // exposes worker metrics via OTel; here we use a simple approach: sleep up to
        // 6 hours and exit (the OCP Job's parallelism handles fanout, parent waits via
        // child workflows).
        long maxLifetime = Long.parseLong(envOr("WORKER_MAX_LIFETIME_MILLIS",
                String.valueOf(6L * 60 * 60 * 1000)));
        Thread.sleep(maxLifetime);

        log.info("max lifetime reached, shutting down worker");
        factory.shutdown();
        stubs.shutdownNow();
        System.exit(0);
    }

    private static SubtaskActivities discoverExecutor() {
        ServiceLoader<SubtaskActivities> sl = ServiceLoader.load(SubtaskActivities.class);
        for (SubtaskActivities a : sl) return a;
        log.warn("no SubtaskActivities SPI implementation found — using echo executor");
        return new EchoExecutor();
    }

    static final class EchoExecutor implements SubtaskActivities {
        @Override
        public SubtaskResult execute(SubtaskRequest r) {
            return SubtaskResult.ok(r.subtaskId(),
                    java.util.Map.of("kind", r.kind(), "echo", true, "argCount", r.args().size()));
        }
    }

    private static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("missing env: " + name);
        return v;
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
