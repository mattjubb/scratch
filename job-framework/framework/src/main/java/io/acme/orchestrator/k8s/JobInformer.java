package io.acme.orchestrator.k8s;

import io.acme.orchestrator.dag.DependencyEngine;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Runs a fabric8 {@link SharedIndexInformer} over Jobs managed by this
 * orchestrator and forwards terminal transitions to the {@link DependencyEngine}.
 * <p>
 * Informer callbacks arrive on fabric8's worker threads; we hop to the Vert.x
 * event loop before touching engine state to preserve the engine's
 * single-threaded contract.
 */
public final class JobInformer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobInformer.class);

    private final KubernetesClient client;
    private final Vertx vertx;
    private final DependencyEngine engine;
    private final String namespace;
    private SharedIndexInformer<Job> informer;

    public JobInformer(KubernetesClient client, Vertx vertx,
                       DependencyEngine engine, String namespace) {
        this.client = client;
        this.vertx = vertx;
        this.engine = engine;
        this.namespace = namespace;
    }

    public void start() {
        informer = client.batch().v1().jobs()
                .inNamespace(namespace)
                .withLabel(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY)
                .withLabel(Labels.KEY_KIND, Labels.VAL_KIND_JOB)
                .inform(new ResourceEventHandler<>() {
                    @Override public void onAdd(Job j) { handle(j); }
                    @Override public void onUpdate(Job oldJ, Job newJ) { handle(newJ); }
                    @Override public void onDelete(Job j, boolean finalState) { /* no-op */ }
                });
        log.info("job informer started on namespace {}", namespace);
    }

    private void handle(Job job) {
        if (job == null || job.getStatus() == null) return;
        List<JobCondition> conds = job.getStatus().getConditions();
        if (conds == null) return;

        var labels = job.getMetadata().getLabels();
        var annotations = job.getMetadata().getAnnotations();
        if (labels == null) return;

        String logicalName = labels.get(Labels.KEY_LOGICAL_NAME);
        String runId = labels.get(Labels.KEY_RUN_ID);
        if (logicalName == null || runId == null) return;

        // fireTime is required so cold-start replay can still apply the
        // scheduling-window rule correctly. If the annotation is missing
        // (shouldn't happen for jobs we created, but be defensive), fall
        // back to the Job's creation timestamp.
        Instant fireTime;
        String ann = annotations != null ? annotations.get(Labels.ANN_FIRE_TIME) : null;
        if (ann != null) {
            fireTime = Instant.parse(ann);
        } else if (job.getMetadata().getCreationTimestamp() != null) {
            fireTime = Instant.parse(job.getMetadata().getCreationTimestamp());
        } else {
            fireTime = Instant.now();
        }

        for (JobCondition c : conds) {
            if (!"True".equalsIgnoreCase(c.getStatus())) continue;
            boolean succeeded = "Complete".equals(c.getType());
            boolean failed    = "Failed".equals(c.getType());
            if (!succeeded && !failed) continue;

            Instant finishedAt = c.getLastTransitionTime() != null
                    ? Instant.parse(c.getLastTransitionTime())
                    : Instant.now();

            // Hop to the event loop before mutating engine state.
            vertx.runOnContext(v ->
                    engine.onJobTerminal(logicalName, runId, succeeded, fireTime, finishedAt));
            return;
        }
    }

    @Override
    public void close() {
        if (informer != null) informer.close();
    }
}
