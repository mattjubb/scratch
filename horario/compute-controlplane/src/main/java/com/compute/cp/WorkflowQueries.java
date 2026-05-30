package com.compute.cp;

import com.compute.model.JobDefinition;
import com.compute.model.JobState;
import com.compute.model.JobStatus;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.compute.model.ServiceDefinition;
import com.compute.model.ServiceState;
import com.compute.model.ServiceStatus;
import com.compute.model.TaskState;
import com.compute.temporal.WorkflowIds;
import com.compute.temporal.workflow.JobWorkflow;
import com.compute.temporal.workflow.ServiceWorkflow;
import com.compute.temporal.workflow.TaskWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Projects Temporal workflow state into the JSON shape the UI consumes.
 *
 * <p>This class is <em>read-only</em>: it never creates or registers Temporal namespaces.
 * The namespace name is derived deterministically from the {@code LaneRef} (no gRPC call
 * needed), so this class adds zero latency when Temporal is unavailable. The only
 * network call is the {@code QueryWorkflow} RPC on {@code stub.getState()}, which fails
 * fast with a {@code WorkflowNotFoundException} when the workflow has not been started
 * yet and returns the STOPPED / SCHEDULED fallback row.</p>
 */
public final class WorkflowQueries {

    private static final Logger log = LoggerFactory.getLogger(WorkflowQueries.class);

    /** Per-call deadline on raw blocking-stub calls (e.g. listWorkflowExecutions). */
    private static final long LIST_DEADLINE_SECONDS = 5;

    private final WorkflowServiceStubs stubs;

    /** Kept for API compatibility with callers that pass a NamespaceResolver. */
    public WorkflowQueries(WorkflowServiceStubs stubs, Object ignoredNamespaceResolver) {
        this.stubs = stubs;
    }

    public List<Map<String, Object>> services(List<ServiceDefinition> defs) {
        List<Map<String, Object>> out = new ArrayList<>(defs.size());
        for (ServiceDefinition d : defs) {
            // Namespace name is purely derived from the LaneRef — no gRPC call required.
            String ns = d.laneRef().temporalNamespace();
            try {
                WorkflowClient c = clientFor(ns);
                ServiceWorkflow stub = c.newWorkflowStub(ServiceWorkflow.class, WorkflowIds.service(d.id()));
                ServiceState s = stub.getState();
                out.add(toUiRow(d, s));
            } catch (Exception e) {
                log.debug("service {} not yet started: {}", d.id().path(), e.getMessage());
                out.add(toUiRow(d, ServiceState.initial(d).withStatus(ServiceStatus.STOPPED, "not started")));
            }
        }
        return out;
    }

    public List<Map<String, Object>> jobs(List<JobDefinition> defs, LocalDate runDate) {
        List<Map<String, Object>> out = new ArrayList<>(defs.size());
        for (JobDefinition d : defs) {
            // Scheduler jobs use a fixed workflow ID (no date suffix) because they are owned
            // by a Temporal Schedule and always execute as a single, non-date-keyed workflow.
            String wfId = d.scheduler()
                    ? WorkflowIds.schedulerJob(d.id())
                    : WorkflowIds.job(d.id(), runDate);
            String ns = d.laneRef().temporalNamespace();
            try {
                WorkflowClient c = clientFor(ns);
                JobWorkflow stub = c.newWorkflowStub(JobWorkflow.class, wfId);
                JobState s = stub.getState();
                out.add(toUiRow(d, s, runDate));
            } catch (Exception e) {
                log.debug("job {} not yet started: {}", d.id().path(), e.getMessage());
                out.add(toUiRow(d, scheduledStub(d, runDate), runDate));
            }
        }
        return out;
    }

    public Map<String, Object> task(LaneRef ref, String taskId) {
        String ns = ref.temporalNamespace();
        try {
            WorkflowClient c = clientFor(ns);
            TaskWorkflow stub = c.newWorkflowStub(TaskWorkflow.class, WorkflowIds.task(taskId));
            TaskState s = stub.getState();
            return toUiRow(s);
        } catch (Exception e) {
            log.warn("task {} state unavailable: {}", taskId, e.getMessage());
            return Map.of("taskId", taskId, "status", "PENDING");
        }
    }

    public List<Map<String, Object>> listTasks(List<LaneRef> namespacesToSearch) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LaneRef ref : namespacesToSearch) {
            String ns = ref.temporalNamespace();
            WorkflowClient c = clientFor(ns);
            String pageToken = "";
            do {
                ListWorkflowExecutionsRequest.Builder req = ListWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(ns)
                        .setQuery("WorkflowType='TaskWorkflow'")
                        .setPageSize(100);
                if (!pageToken.isEmpty())
                    req.setNextPageToken(com.google.protobuf.ByteString.copyFromUtf8(pageToken));
                try {
                    // Use an explicit per-call deadline so the raw blocking stub doesn't hang.
                    ListWorkflowExecutionsResponse resp = stubs.blockingStub()
                            .withDeadlineAfter(LIST_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .listWorkflowExecutions(req.build());
                    for (WorkflowExecutionInfo info : resp.getExecutionsList()) {
                        WorkflowExecution exec = info.getExecution();
                        String wfId = exec.getWorkflowId();
                        if (!wfId.startsWith("task:")) continue;
                        String taskId = wfId.substring("task:".length());
                        try {
                            TaskWorkflow stub = c.newWorkflowStub(TaskWorkflow.class, wfId);
                            TaskState s = stub.getState();
                            out.add(toUiRow(s));
                        } catch (Exception ignore) {
                            out.add(Map.of("taskId", taskId, "status", "PENDING"));
                        }
                    }
                    pageToken = resp.getNextPageToken().toStringUtf8();
                } catch (Exception e) {
                    log.debug("list tasks failed in {}: {}", ns, e.getMessage());
                    break;
                }
            } while (!pageToken.isEmpty());
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private WorkflowClient clientFor(String namespace) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    private static Map<String, Object> toUiRow(ServiceDefinition d, ServiceState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id().path());
        m.put("path", d.id().path());
        m.put("fullPath", d.id().path());
        m.put("group", d.group());
        m.put("project", d.project());
        m.put("lane", d.lane().code());
        m.put("status", s.status().name().toLowerCase());
        m.put("startTime", s.startTime() == null ? null : s.startTime().toEpochMilli());
        m.put("endTime", s.status().isTerminal() ? s.lastTransition().toEpochMilli() : null);
        m.put("desiredReplicas", s.desiredReplicas());
        m.put("readyReplicas", s.readyReplicas());
        m.put("tag", d.version());
        m.put("host", "");
        return m;
    }

    private static Map<String, Object> toUiRow(JobDefinition d, JobState s, LocalDate runDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id().path());
        m.put("path", d.id().path());
        m.put("fullPath", d.id().path());
        m.put("group", d.group());
        m.put("project", d.project());
        m.put("lane", d.lane().code());
        m.put("status", uiJobStatus(s.status()));
        m.put("startTime", s.startTime() == null ? null : s.startTime().toEpochMilli());
        m.put("endTime", s.endTime() == null ? null : s.endTime().toEpochMilli());
        m.put("scheduledTime", s.scheduledTime() == null ? null : s.scheduledTime().toEpochMilli());
        m.put("runDate", runDate.toString());
        m.put("tag", d.version());
        m.put("host", "");
        m.put("manuallyTriggered", s.manuallyTriggered());
        return m;
    }

    private static Map<String, Object> toUiRow(TaskState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", s.taskId());
        m.put("id", s.taskId());
        m.put("group", s.group());
        m.put("project", s.project());
        m.put("lane", s.lane().code());
        m.put("status", s.status().name().toLowerCase());
        m.put("subtaskCount", s.totalSubtasks());
        m.put("completedCount", s.completedSubtasks());
        m.put("failedCount", s.failedSubtasks());
        m.put("runningCount", s.runningSubtasks());
        m.put("startTime", s.startTime() == null ? null : s.startTime().toEpochMilli());
        m.put("endTime", s.endTime() == null ? null : s.endTime().toEpochMilli());
        return m;
    }

    private static String uiJobStatus(JobStatus s) {
        return switch (s) {
            case SCHEDULED    -> "pending";
            case WAITING_DEPS -> "pending";
            case STARTING     -> "starting";
            case RUNNING      -> "running";
            case COMPLETED    -> "completed";
            case FAILED       -> "failed";
            case SKIPPED      -> "stopped";
        };
    }

    private static JobState scheduledStub(JobDefinition d, LocalDate runDate) {
        return new JobState(d.id(), d.lane(), d.group(), d.project(), runDate,
                JobStatus.SCHEDULED, Instant.now(), null, null, 0, "",
                d.defaultArgs(), false);
    }

    /** Keep Lane import meaningful when callers pass it directly. */
    @SuppressWarnings("unused")
    private static Lane unused(Lane l) { return l; }
}
