package com.compute.cp;

import com.compute.image.ImageVersionClient;
import com.compute.model.JobDefinition;
import com.compute.model.LaneRef;
import com.compute.ocp.DeploymentApplier;
import com.compute.ocp.JobApplier;
import com.compute.ocp.LogStreamer;
import com.compute.ocp.OcpClientHolder;
import com.compute.ocp.PodSpecComposer;
import com.compute.temporal.ControlPlaneWorkers;
import com.compute.temporal.NamespaceResolver;
import com.compute.temporal.TaskQueues;
import com.compute.temporal.TemporalConfig;
import com.compute.temporal.WorkflowIds;
import com.compute.temporal.activity.impl.DefinitionActivitiesImpl;
import com.compute.temporal.activity.impl.ImageActivitiesImpl;
import com.compute.temporal.activity.impl.OcpActivitiesImpl;
import com.compute.temporal.activity.impl.OrchestrationActivitiesImpl;
import com.compute.temporal.workflow.JobWorkflow;
import com.compute.yaml.DefinitionLoader;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.client.schedules.ScheduleUpdateInput;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.workflow.Functions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ControlPlaneVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneVerticle.class);

    private final ControlPlaneConfig config;
    private final TemporalConfig temporalConfig;
    private WorkflowServiceStubs stubs;
    private OcpClientHolder ocp;
    private ControlPlaneWorkers workers;
    private ImageVersionClient imageClient;
    private StatePublisher publisher;

    public ControlPlaneVerticle(ControlPlaneConfig config, TemporalConfig temporalConfig) {
        this.config = config;
        this.temporalConfig = temporalConfig;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        try {
            log.info("control-plane starting (dryRun={}, definitions={}, temporal={})",
                    config.dryRun(), config.definitionsDir(), temporalConfig.target());

            // Temporal stubs — 5 s per-RPC deadline so workflow queries fail fast when
            // Temporal is unavailable rather than blocking indefinitely.
            stubs = WorkflowServiceStubs.newServiceStubs(
                    WorkflowServiceStubsOptions.newBuilder()
                            .setTarget(temporalConfig.target())
                            .setRpcTimeout(Duration.ofSeconds(5))
                            .build());
            NamespaceResolver namespaces = new NamespaceResolver(stubs, temporalConfig.autoCreateNamespaces());

            // OCP + image
            ocp = new OcpClientHolder(config.dryRun(), config.ocpNamespace());
            imageClient = new ImageVersionClient(vertx, config.imageVersionApiBaseUrl());
            PodSpecComposer composer = new PodSpecComposer();
            DeploymentApplier deployments = new DeploymentApplier(ocp, composer);
            JobApplier jobs = new JobApplier(ocp, composer);
            LogStreamer logs = new LogStreamer(ocp);

            // Activities
            DefinitionLoader bootstrapLoader = new DefinitionLoader(config.definitionsDir(), com.compute.model.Lane.DEV);
            var defActs = new DefinitionActivitiesImpl(bootstrapLoader);
            var imgActs = new ImageActivitiesImpl(imageClient);
            var ocpActs = new OcpActivitiesImpl(ocp, deployments, jobs);
            var orchActs = new OrchestrationActivitiesImpl(
                    WorkflowClient.newInstance(stubs), stubs, namespaces);
            // Give orchActs the loader so reconcileProject() can load definitions.
            orchActs.setDefinitionLoader(bootstrapLoader);

            ControlPlaneWorkers.Activities activities =
                    new ControlPlaneWorkers.Activities(defActs, imgActs, ocpActs, orchActs);

            workers = new ControlPlaneWorkers(stubs, namespaces, temporalConfig, activities);

            // Worker startup is best-effort: if Temporal is down the HTTP server still
            // starts and serves STOPPED/SCHEDULED rows from YAML definitions alone.
            List<JobDefinition> schedulerJobs;
            try {
                workers.startSystemWorker();

                // Discover all (group, project, lane) triples and start their workers.
                List<LaneRef> triples = new LaneCatalog(config.definitionsDir()).discover();
                log.info("discovered {} (group, project, lane) triples", triples.size());
                for (LaneRef r : triples) {
                    try { workers.ensureWorker(r); }
                    catch (Exception e) { log.warn("worker startup skipped for {}: {}", r, e.getMessage()); }
                }

                // Create/update Temporal Schedules for scheduler job definitions.
                List<JobDefinition> allJobs = bootstrapLoader.load().jobs();
                schedulerJobs = allJobs.stream()
                        .filter(JobDefinition::scheduler)
                        .filter(j -> !j.schedule().isBlank())
                        .toList();
                log.info("found {} scheduler job definitions", schedulerJobs.size());
                for (JobDefinition sched : schedulerJobs) {
                    ensureSchedulerJobSchedule(sched, namespaces);
                }
            } catch (Exception e) {
                log.warn("Temporal worker startup failed (will retry on next reload): {}", e.getMessage());
                schedulerJobs = List.of();
            }

            // REST + SSE state publisher (pushes Temporal state to browsers every 3 s)
            WorkflowQueries queries = new WorkflowQueries(stubs, namespaces);
            publisher = new StatePublisher(vertx, queries, config.definitionsDir());
            LogStreamRegistry logsReg = new LogStreamRegistry(logs);
            RestRouter router = new RestRouter(vertx, config.definitionsDir(), stubs,
                    namespaces, queries, logsReg, schedulerJobs);

            vertx.createHttpServer()
                    .requestHandler(router.build())
                    .listen(config.httpPort())
                    .onSuccess(s -> {
                        log.info("HTTP listening on :{}", config.httpPort());
                        publisher.start(3_000L);
                        startPromise.complete();
                    })
                    .onFailure(startPromise::fail);

        } catch (Exception e) {
            startPromise.fail(e);
        }
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        try { if (publisher != null) publisher.stop(); } catch (Exception ignore) {}
        try { if (workers != null) workers.close(); } catch (Exception ignore) {}
        try { if (imageClient != null) imageClient.close(); } catch (Exception ignore) {}
        try { if (stubs != null) stubs.shutdownNow(); } catch (Exception ignore) {}
        try { if (ocp != null) ocp.close(); } catch (Exception ignore) {}
        stopPromise.complete();
    }

    /**
     * Creates or updates the Temporal Schedule for a scheduler job. The Schedule fires
     * the {@code JobWorkflow} at the job's configured cron time, which then calls
     * {@code reconcileProject} instead of spinning up an OCP container.
     */
    private void ensureSchedulerJobSchedule(JobDefinition sched, NamespaceResolver namespaces) {
        try {
            String ns = namespaces.ensure(sched.laneRef());
            String scheduleId = WorkflowIds.scheduleId(sched.id());
            String wfId = WorkflowIds.schedulerJob(sched.id());
            String taskQueue = TaskQueues.forNamespace(ns);

            ScheduleClient schedClient = ScheduleClient.newInstance(stubs,
                    ScheduleClientOptions.newBuilder().setNamespace(ns).build());

            Schedule schedule = buildSchedule(sched, wfId, taskQueue);

            try {
                schedClient.createSchedule(scheduleId, schedule,
                        ScheduleOptions.newBuilder()
                                .setTriggerImmediately(true)
                                .build());
                log.info("created Schedule '{}' for scheduler job {} (cron: '{}')",
                        scheduleId, sched.id().path(), sched.schedule());
            } catch (io.temporal.client.schedules.ScheduleAlreadyRunningException exists) {
                ScheduleHandle handle = schedClient.getHandle(scheduleId);
                Schedule finalSchedule = schedule;
                handle.update((Functions.Func1<ScheduleUpdateInput, ScheduleUpdate>) input ->
                        new ScheduleUpdate(finalSchedule));
                handle.trigger();
                log.info("updated + triggered existing Schedule '{}' for {}", scheduleId, sched.id().path());
            }
        } catch (Exception e) {
            log.warn("ensureSchedulerJobSchedule failed for {}: {}", sched.id().path(), e.getMessage());
        }
    }

    private Schedule buildSchedule(JobDefinition sched, String wfId, String taskQueue) {
        return Schedule.newBuilder()
                .setAction(ScheduleActionStartWorkflow.newBuilder()
                        .setWorkflowType(JobWorkflow.class)
                        .setArguments(sched, (Object) null)   // runDate = null → derive from Workflow.currentTimeMillis()
                        .setOptions(WorkflowOptions.newBuilder()
                                .setWorkflowId(wfId)
                                .setTaskQueue(taskQueue)
                                .build())
                        .build())
                .setSpec(ScheduleSpec.newBuilder()
                        .setCronExpressions(List.of(sched.schedule()))
                        .build())
                .setPolicy(SchedulePolicy.newBuilder()
                        .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                        .build())
                .build();
    }
}
