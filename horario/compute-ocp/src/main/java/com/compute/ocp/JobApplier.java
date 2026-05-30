package com.compute.ocp;

import com.compute.model.ComputeId;
import com.compute.model.ImageSpec;
import com.compute.model.JobDefinition;
import com.compute.model.LaneRef;
import com.compute.model.TaskRequest;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates / watches / deletes OCP Jobs for both scheduled jobs and ephemeral
 * task-worker batches.
 */
public final class JobApplier {

    private static final Logger log = LoggerFactory.getLogger(JobApplier.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OcpClientHolder ocp;
    private final PodSpecComposer composer;

    public JobApplier(OcpClientHolder ocp, PodSpecComposer composer) {
        this.ocp = ocp;
        this.composer = composer;
    }

    public Job applyForJob(JobDefinition def, List<ImageSpec> images,
                           LocalDate runDate, Map<String, String> args) {
        String name = ResourceNamer.jobName(def.id(), runDate.format(YMD));
        Map<String, String> labels = jobLabels(def, runDate);

        List<String> cmdArgs = new ArrayList<>();
        args.forEach((k, v) -> cmdArgs.add("--" + k + "=" + v));

        PodSpec podSpec = composer.compose(new PodSpecComposer.Params()
                .images(images)
                .mainClass(def.mainClass())
                .args(cmdArgs)
                .env(def.env())
                .resources(def.resources())
                .restartPolicy("Never"));

        Job j = new JobBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(ocp.namespace())
                        .withLabels(labels)
                        .build())
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withCompletions(1)
                    .withParallelism(1)
                    .withNewTemplate()
                        .withMetadata(new ObjectMetaBuilder().withLabels(labels).build())
                        .withSpec(podSpec)
                    .endTemplate()
                .endSpec()
                .build();

        return apply(name, j);
    }

    public Job applyForTaskWorkers(TaskRequest task, List<ImageSpec> images,
                                   String temporalTarget, String temporalNamespace) {
        String name = ResourceNamer.taskJobName(task.taskId());
        Map<String, String> labels = taskWorkerLabels(task);

        Map<String, String> env = new LinkedHashMap<>();
        env.put("TASK_ID", task.taskId());
        env.put("TASK_QUEUE", task.taskQueue());
        env.put("TEMPORAL_TARGET", temporalTarget);
        env.put("TEMPORAL_NAMESPACE", temporalNamespace);
        env.put("TASK_PARALLELISM", String.valueOf(task.parallelism()));

        PodSpec podSpec = composer.compose(new PodSpecComposer.Params()
                .images(images)
                .mainClass("com.compute.subtask.WorkerMain")
                .args(List.of())
                .extraEnv(env)
                .restartPolicy("Never"));

        Job j = new JobBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(ocp.namespace())
                        .withLabels(labels)
                        .build())
                .withNewSpec()
                    .withBackoffLimit(2)
                    .withCompletions(task.parallelism())
                    .withParallelism(task.parallelism())
                    .withCompletionMode("Indexed")
                    .withNewTemplate()
                        .withMetadata(new ObjectMetaBuilder().withLabels(labels).build())
                        .withSpec(podSpec)
                    .endTemplate()
                .endSpec()
                .build();

        return apply(name, j);
    }

    public Outcome poll(String name) {
        if (ocp.isDryRun()) return Outcome.RUNNING;
        Job j = ocp.client().batch().v1().jobs().inNamespace(ocp.namespace()).withName(name).get();
        if (j == null) {
            // Job no longer exists (deleted externally, or was never created successfully).
            // Returning PENDING here would cause an infinite poll loop — treat as FAILED.
            log.warn("OCP Job {} not found in namespace {} — treating as FAILED",
                    name, ocp.namespace());
            return Outcome.FAILED;
        }
        if (j.getStatus() == null) return Outcome.PENDING;
        JobStatus s = j.getStatus();
        if (s.getSucceeded() != null && s.getSucceeded() > 0) return Outcome.SUCCEEDED;
        if (s.getFailed() != null && s.getFailed() > 0) return Outcome.FAILED;
        if (s.getActive() != null && s.getActive() > 0) {
            // Pod is active — distinguish init-container phase from main container running.
            return mainContainerRunning(name) ? Outcome.RUNNING : Outcome.STARTING;
        }
        return Outcome.PENDING;
    }

    /**
     * Returns {@code true} if the {@code app} container inside any pod of this Job
     * is in the Running state (i.e. all init containers have finished).
     */
    private boolean mainContainerRunning(String jobName) {
        try {
            var pods = ocp.client().pods()
                    .inNamespace(ocp.namespace())
                    .withLabel("job-name", jobName)
                    .list().getItems();
            for (var pod : pods) {
                var containerStatuses = pod.getStatus() != null
                        ? pod.getStatus().getContainerStatuses() : null;
                if (containerStatuses == null) continue;
                for (var cs : containerStatuses) {
                    if ("app".equals(cs.getName())
                            && cs.getState() != null
                            && cs.getState().getRunning() != null) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("mainContainerRunning check failed for job {}: {}", jobName, e.getMessage());
        }
        return false;
    }

    public void delete(String name) {
        if (ocp.isDryRun()) {
            log.info("[dry-run] would delete Job {}", name);
            return;
        }
        ocp.client().batch().v1().jobs().inNamespace(ocp.namespace())
                .withName(name).delete();
    }

    private Job apply(String name, Job j) {
        if (ocp.isDryRun()) {
            log.info("[dry-run] would apply Job {}", name);
            return j;
        }
        log.info("apply Job {}", name);
        return ocp.client().batch().v1().jobs().inNamespace(ocp.namespace()).resource(j).serverSideApply();
    }

    private static Map<String, String> jobLabels(JobDefinition def, LocalDate date) {
        Map<String, String> m = new LinkedHashMap<>(ResourceNamer.baseLabels(def.laneRef()));
        m.put(ResourceNamer.LABEL_KIND, "job");
        m.put(ResourceNamer.LABEL_ID, ResourceNamer.sanitize(def.id()));
        m.put(ResourceNamer.LABEL_VERSION, def.version());
        m.put(ResourceNamer.LABEL_RUN_DATE, date.format(YMD));
        return m;
    }

    private static Map<String, String> taskWorkerLabels(TaskRequest t) {
        Map<String, String> m = new LinkedHashMap<>(ResourceNamer.baseLabels(t.laneRef()));
        m.put(ResourceNamer.LABEL_KIND, "task-worker");
        m.put(ResourceNamer.LABEL_VERSION, t.version());
        m.put(ResourceNamer.LABEL_TASK_ID, t.taskId());
        return m;
    }

    /** Avoid unused import warning for k8s EnvVar — kept for future direct env injection. */
    @SuppressWarnings("unused")
    private static EnvVar unused() { return null; }

    public enum Outcome {
        /** Job not yet active (pod not scheduled). */
        PENDING,
        /** Pod active; init containers still running — main container not yet started. */
        STARTING,
        /** Main ({@code app}) container is executing. */
        RUNNING,
        SUCCEEDED, FAILED
    }
}
