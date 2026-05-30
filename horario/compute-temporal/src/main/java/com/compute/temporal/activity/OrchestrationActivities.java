package com.compute.temporal.activity;

import com.compute.model.JobDefinition;
import com.compute.model.JobStatus;
import com.compute.model.Lane;
import com.compute.model.ServiceDefinition;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.time.LocalDate;
import java.util.List;

/**
 * Cross-workflow orchestration that the scheduler delegates to (start/stop service
 * workflows in other namespaces, schedule today's jobs).
 */
@ActivityInterface
public interface OrchestrationActivities {

    @ActivityMethod
    void ensureServiceWorkflow(ServiceDefinition def);

    @ActivityMethod
    void stopServiceWorkflow(String idPath, String group, String project, Lane lane);

    @ActivityMethod
    void scheduleJobWorkflow(JobDefinition def, LocalDate runDate);

    @ActivityMethod
    List<String> listRunningServiceIds(String group, String project, Lane lane);

    /**
     * Performs a full project reconcile: ensures services are running, pre-populates job
     * workflows for today through today + lookaheadDays, and cancels orphaned job workflows
     * whose definitions have been removed. Called by a scheduler {@code JobWorkflow} instead
     * of spinning up an OCP container.
     */
    @ActivityMethod
    void reconcileProject(String group, String project, Lane lane, int lookaheadDays);

    /**
     * Returns the full Temporal workflow IDs (e.g. {@code "job:/rates/eod/curve-build:2026-05-25"})
     * for all currently-running {@code JobWorkflow} executions in the given namespace.
     * Used by {@code ProjectSchedulerWorkflow} to detect and cancel orphaned job workflows
     * when a job definition is removed.
     */
    @ActivityMethod
    List<String> listRunningJobWorkflowIds(String group, String project, Lane lane);

    /**
     * Sends a {@code cancel()} signal to the {@code JobWorkflow} identified by
     * {@code workflowId} in the given namespace. Silently ignores failures (the
     * workflow may have already completed).
     */
    @ActivityMethod
    void cancelJobByWorkflowId(String workflowId, String group, String project, Lane lane);

    /** Used by JobWorkflow to poll a dep job's status across namespaces. */
    @ActivityMethod
    JobStatus queryJobStatus(String idPath, String group, String project, Lane lane, LocalDate runDate);
}
