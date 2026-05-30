package com.compute.temporal.activity.impl;

import com.compute.model.JobDefinition;
import com.compute.model.JobState;
import com.compute.model.JobStatus;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.compute.model.ServiceDefinition;
import com.compute.model.ServiceState;
import com.compute.temporal.NamespaceResolver;
import com.compute.yaml.DefinitionLoader;
import com.compute.temporal.TaskQueues;
import com.compute.temporal.WorkflowIds;
import com.compute.temporal.activity.OrchestrationActivities;
import com.compute.temporal.workflow.JobWorkflow;
import com.compute.temporal.workflow.ServiceWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OrchestrationActivitiesImpl implements OrchestrationActivities {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationActivitiesImpl.class);

    private final WorkflowClient client;
    private final WorkflowServiceStubs stubs;
    private final NamespaceResolver namespaces;
    private DefinitionLoader definitionLoader; // set via setter to break circular dependency

    public OrchestrationActivitiesImpl(WorkflowClient client, WorkflowServiceStubs stubs,
                                       NamespaceResolver namespaces) {
        this.client = client;
        this.stubs = stubs;
        this.namespaces = namespaces;
    }

    /** Inject the definition loader used by {@link #reconcileProject}. */
    public void setDefinitionLoader(DefinitionLoader loader) {
        this.definitionLoader = loader;
    }

    @Override
    public void ensureServiceWorkflow(ServiceDefinition def) {
        String ns = namespaces.ensure(def.laneRef());
        WorkflowClient nsClient = WorkflowClient.newInstance(stubs,
                io.temporal.client.WorkflowClientOptions.newBuilder().setNamespace(ns).build());
        // ALLOW_DUPLICATE: a service stopped or completed may be restarted.
        // Temporal guarantees only one execution per workflow ID can be RUNNING at a time;
        // a concurrent start attempt while the service is already running throws
        // WorkflowExecutionAlreadyStarted, which we catch below and convert to a redeploy signal.
        ServiceWorkflow stub = nsClient.newWorkflowStub(ServiceWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(WorkflowIds.service(def.id()))
                        .setTaskQueue(TaskQueues.forNamespace(ns))
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                        .build());
        try {
            WorkflowClient.start(stub::run, def);
            log.info("started ServiceWorkflow {}", WorkflowIds.service(def.id()));
        } catch (WorkflowExecutionAlreadyStarted e) {
            // Already running — send redeploy in case the definition changed.
            // This is the sole mechanism by which a second instance could start; instead
            // of allowing it, we signal the existing instance to update itself.
            log.info("ServiceWorkflow {} already running — signalling redeploy", WorkflowIds.service(def.id()));
            stub.redeploy(def);
        }
    }

    @Override
    public void stopServiceWorkflow(String idPath, String group, String project, Lane lane) {
        String ns = namespaces.ensure(new LaneRef(group, project, lane));
        WorkflowClient nsClient = WorkflowClient.newInstance(stubs,
                io.temporal.client.WorkflowClientOptions.newBuilder().setNamespace(ns).build());
        ServiceWorkflow stub = nsClient.newWorkflowStub(ServiceWorkflow.class, "service:" + idPath);
        try {
            stub.stop();
        } catch (Exception e) {
            log.warn("stop signal failed for {}: {}", idPath, e.getMessage());
        }
    }

    @Override
    public void scheduleJobWorkflow(JobDefinition def, LocalDate runDate) {
        String ns = namespaces.ensure(def.laneRef());
        WorkflowClient nsClient = WorkflowClient.newInstance(stubs,
                io.temporal.client.WorkflowClientOptions.newBuilder().setNamespace(ns).build());
        // ALLOW_DUPLICATE_FAILED_ONLY: the scheduler may reschedule a job that previously
        // failed or timed out (automatic retry on the next cycle) but must never re-trigger
        // a job that already COMPLETED successfully for the same date.
        // If the job is currently RUNNING, WorkflowExecutionAlreadyStarted is thrown and
        // silently ignored — only one execution per (job, date) can be in flight.
        JobWorkflow stub = nsClient.newWorkflowStub(JobWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(WorkflowIds.job(def.id(), runDate))
                        .setTaskQueue(TaskQueues.forNamespace(ns))
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                        .build());
        try {
            WorkflowClient.start(stub::run, def, runDate);
            log.info("scheduled JobWorkflow {}", WorkflowIds.job(def.id(), runDate));
        } catch (WorkflowExecutionAlreadyStarted ignore) {
            // Already running or already completed successfully — both are correct; skip.
        }
    }

    @Override
    public List<String> listRunningServiceIds(String group, String project, Lane lane) {
        String ns = namespaces.ensure(new LaneRef(group, project, lane));
        List<String> out = new ArrayList<>();
        String query = "ExecutionStatus='Running' AND WorkflowType='ServiceWorkflow'";
        String pageToken = "";
        do {
            ListWorkflowExecutionsRequest.Builder req = ListWorkflowExecutionsRequest.newBuilder()
                    .setNamespace(ns).setQuery(query).setPageSize(100);
            if (!pageToken.isEmpty()) req.setNextPageToken(com.google.protobuf.ByteString.copyFromUtf8(pageToken));
            ListWorkflowExecutionsResponse resp = stubs.blockingStub().listWorkflowExecutions(req.build());
            for (WorkflowExecutionInfo info : resp.getExecutionsList()) {
                WorkflowExecution exec = info.getExecution();
                String wfId = exec.getWorkflowId();
                if (wfId.startsWith("service:")) out.add(wfId.substring("service:".length()));
            }
            pageToken = resp.getNextPageToken().toStringUtf8();
        } while (!pageToken.isEmpty());
        return out;
    }

    @Override
    public void reconcileProject(String group, String project, Lane lane, int lookaheadDays) {
        if (definitionLoader == null) {
            log.warn("reconcileProject called but no DefinitionLoader injected; skipping");
            return;
        }
        log.info("reconciling project {}/{} (lookaheadDays={})", group, project, lookaheadDays);
        DefinitionLoader.Result loaded = definitionLoader.loadForProject(group, project);

        // ── Services ─────────────────────────────────────────────────────────────────
        java.util.Set<String> yamlServicePaths = new java.util.HashSet<>();
        for (ServiceDefinition svc : loaded.services()) {
            yamlServicePaths.add(svc.id().path());
            try {
                ensureServiceWorkflow(svc);
            } catch (Exception e) {
                log.warn("ensureServiceWorkflow failed for {}: {}", svc.id().path(), e.getMessage());
            }
        }
        for (String runId : listRunningServiceIds(group, project, lane)) {
            if (!yamlServicePaths.contains(runId)) {
                log.info("stopping removed service: {}", runId);
                try { stopServiceWorkflow(runId, group, project, lane); }
                catch (Exception e) { log.warn("stopServiceWorkflow failed for {}: {}", runId, e.getMessage()); }
            }
        }

        // ── Jobs ─────────────────────────────────────────────────────────────────────
        // Exclude the scheduler job itself from the list of jobs to pre-populate.
        java.util.Set<String> yamlJobPaths = new java.util.HashSet<>();
        java.util.List<JobDefinition> regularJobs = new java.util.ArrayList<>();
        for (JobDefinition job : loaded.jobs()) {
            yamlJobPaths.add(job.id().path());
            if (!job.scheduler()) regularJobs.add(job);
        }

        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        for (int d = 0; d <= lookaheadDays; d++) {
            LocalDate date = today.plusDays(d);
            for (JobDefinition job : regularJobs) {
                try { scheduleJobWorkflow(job, date); }
                catch (Exception e) { log.warn("scheduleJobWorkflow failed for {} on {}: {}",
                        job.id().path(), date, e.getMessage()); }
            }
        }

        // Cancel running job workflows for definitions that were removed.
        for (String wfId : listRunningJobWorkflowIds(group, project, lane)) {
            String path = jobPathFromWorkflowId(wfId);
            if (path != null && !yamlJobPaths.contains(path)) {
                log.info("cancelling orphaned job workflow: {}", wfId);
                try { cancelJobByWorkflowId(wfId, group, project, lane); }
                catch (Exception e) { log.warn("cancel failed for {}: {}", wfId, e.getMessage()); }
            }
        }
        log.info("reconcile complete for {}/{}", group, project);
    }

    private static String jobPathFromWorkflowId(String wfId) {
        if (!wfId.startsWith("job:")) return null;
        String rest = wfId.substring("job:".length());
        int lastColon = rest.lastIndexOf(':');
        if (lastColon <= 0) return null;
        return rest.substring(0, lastColon);
    }

    @Override
    public List<String> listRunningJobWorkflowIds(String group, String project, Lane lane) {
        String ns = namespaces.ensure(new LaneRef(group, project, lane));
        List<String> out = new ArrayList<>();
        String query = "ExecutionStatus='Running' AND WorkflowType='JobWorkflow'";
        String pageToken = "";
        do {
            ListWorkflowExecutionsRequest.Builder req = ListWorkflowExecutionsRequest.newBuilder()
                    .setNamespace(ns).setQuery(query).setPageSize(100);
            if (!pageToken.isEmpty()) req.setNextPageToken(com.google.protobuf.ByteString.copyFromUtf8(pageToken));
            ListWorkflowExecutionsResponse resp = stubs.blockingStub().listWorkflowExecutions(req.build());
            for (WorkflowExecutionInfo info : resp.getExecutionsList()) {
                out.add(info.getExecution().getWorkflowId());
            }
            pageToken = resp.getNextPageToken().toStringUtf8();
        } while (!pageToken.isEmpty());
        return out;
    }

    @Override
    public void cancelJobByWorkflowId(String workflowId, String group, String project, Lane lane) {
        String ns = namespaces.ensure(new LaneRef(group, project, lane));
        WorkflowClient nsClient = WorkflowClient.newInstance(stubs,
                io.temporal.client.WorkflowClientOptions.newBuilder().setNamespace(ns).build());
        try {
            nsClient.newWorkflowStub(com.compute.temporal.workflow.JobWorkflow.class, workflowId).cancel();
            log.info("cancelled JobWorkflow {}", workflowId);
        } catch (Exception e) {
            log.warn("cancelJobByWorkflowId {} failed (may have already completed): {}", workflowId, e.getMessage());
        }
    }

    @Override
    public JobStatus queryJobStatus(String idPath, String group, String project, Lane lane, LocalDate runDate) {
        String ns = namespaces.ensure(new LaneRef(group, project, lane));
        WorkflowClient nsClient = WorkflowClient.newInstance(stubs,
                io.temporal.client.WorkflowClientOptions.newBuilder().setNamespace(ns).build());
        try {
            JobWorkflow stub = nsClient.newWorkflowStub(JobWorkflow.class,
                    "job:" + idPath + ":" + runDate.toString());
            JobState s = stub.getState();
            return s == null ? JobStatus.SCHEDULED : s.status();
        } catch (Exception e) {
            return JobStatus.SCHEDULED;
        }
    }

    /** Keep ServiceState import meaningful (callers may consume via UI projection). */
    @SuppressWarnings("unused")
    private static ServiceState unused(ServiceState s) { return s; }
}
