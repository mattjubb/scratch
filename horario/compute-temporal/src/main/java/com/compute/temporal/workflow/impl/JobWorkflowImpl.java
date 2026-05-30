package com.compute.temporal.workflow.impl;

import com.compute.model.ComputeId;
import com.compute.model.ImageSpec;
import com.compute.model.JobDefinition;
import com.compute.model.JobState;
import com.compute.model.JobStatus;
import com.compute.temporal.activity.ImageActivities;
import com.compute.temporal.activity.OcpActivities;
import com.compute.temporal.activity.OrchestrationActivities;
import com.compute.temporal.workflow.JobWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JobWorkflowImpl implements JobWorkflow {

    private final ImageActivities images = Workflow.newActivityStub(
            ImageActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
                    .build());

    private final OcpActivities ocp = Workflow.newActivityStub(
            OcpActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofHours(6))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private final OrchestrationActivities orch = Workflow.newActivityStub(
            OrchestrationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
                    .build());

    private JobDefinition def;
    private LocalDate runDate;
    private JobState state;
    private boolean cancelRequested;
    private boolean manualRequested;
    private Map<String, String> manualArgs = Map.of();

    @Override
    public void run(JobDefinition definition, LocalDate runDate) {
        this.def = definition;
        // Scheduler jobs are fired by a Temporal Schedule and receive a null runDate.
        // Derive it from the workflow clock so all downstream code sees a non-null value.
        this.runDate = runDate != null ? runDate
                : LocalDate.ofInstant(
                        java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                        java.time.ZoneOffset.UTC);
        this.state = new JobState(
                definition.id(), definition.lane(), definition.group(), definition.project(),
                this.runDate, JobStatus.SCHEDULED,
                java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                null, null, 0, "", new LinkedHashMap<>(definition.defaultArgs()), false);

        // Scheduler jobs reconcile services + pre-populate sibling job runs; no OCP container.
        // Must be checked BEFORE the date-wait block (scheduler jobs have no meaningful runDate
        // to wait for — they should execute immediately when the Schedule fires them).
        if (def.scheduler()) {
            state = state.withStatus(JobStatus.RUNNING, "reconciling project");
            try {
                orch.reconcileProject(def.group(), def.project(), def.lane(), def.lookaheadDays());
                state = state.withStatus(JobStatus.COMPLETED, "reconcile complete").withExit(0, "ok");
            } catch (Exception e) {
                state = state.withStatus(JobStatus.FAILED,
                        "reconcile failed: " + e.getMessage()).withExit(1, "failed");
            }
            return;
        }

        // If pre-scheduled ahead of the run date (lookahead), wait until midnight UTC of
        // runDate before starting — a manual run signal short-circuits this wait.
        long runAtMs = this.runDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        if (Workflow.currentTimeMillis() < runAtMs) {
            long waitMs = runAtMs - Workflow.currentTimeMillis();
            Workflow.await(java.time.Duration.ofMillis(waitMs), () -> cancelRequested || manualRequested);
        }
        if (cancelRequested) { state = state.withStatus(JobStatus.SKIPPED, "cancelled before run date"); return; }

        // Wait for dependencies (or manual run signal short-circuit)
        state = state.withStatus(JobStatus.WAITING_DEPS, "");
        boolean depsOk = waitForDeps();

        if (cancelRequested) { state = state.withStatus(JobStatus.SKIPPED, "cancelled"); return; }
        if (!depsOk && !manualRequested) {
            state = state.withStatus(JobStatus.SKIPPED, "dependencies not met");
            return;
        }

        Map<String, String> effective = new HashMap<>(state.args());
        if (manualRequested) {
            effective.putAll(manualArgs);
            state = new JobState(
                    state.id(), state.lane(), state.group(), state.project(), state.runDate(),
                    state.status(), state.scheduledTime(), state.startTime(), state.endTime(),
                    state.exitCode(), state.message(), effective, true);
        }

        // Create the OCP Job — pod is now pending/scheduling/running init containers.
        List<ImageSpec> imgs = images.fetch(def.group(), def.project(), def.lane(), def.version());
        OcpActivities.JobResult applied = ocp.applyOcpJob(def, imgs, runDate, effective);
        state = state.withStatus(JobStatus.STARTING, "OCP Job created, pod initializing");

        // Poll until done — max 2160 iterations (6 h at 10 s each) as a safety ceiling.
        final int maxPolls = 2160;
        for (int pollCount = 0; pollCount < maxPolls; pollCount++) {
            if (cancelRequested) {
                ocp.deleteOcpJob(applied.jobName());
                state = state.withStatus(JobStatus.FAILED, "cancelled").withExit(130, "cancelled");
                return;
            }
            OcpActivities.JobPoll s = ocp.pollOcpJob(applied.jobName());
            if (s == OcpActivities.JobPoll.RUNNING && state.status() == JobStatus.STARTING) {
                // Main container just came up — promote STARTING → RUNNING.
                state = state.withStatus(JobStatus.RUNNING, "main container running");
            }
            if (s == OcpActivities.JobPoll.SUCCEEDED) {
                state = state.withStatus(JobStatus.COMPLETED, "ok").withExit(0, "ok");
                return;
            }
            if (s == OcpActivities.JobPoll.FAILED) {
                state = state.withStatus(JobStatus.FAILED, "ocp job failed").withExit(1, "failed");
                return;
            }
            Workflow.sleep(Duration.ofSeconds(10));
        }
        // Safety: should not be reached with a healthy OCP job, but guard against infinite loops.
        state = state.withStatus(JobStatus.FAILED, "poll timed out after 6 h").withExit(124, "timeout");
    }

    private boolean waitForDeps() {
        if (def.deps().isEmpty()) return true;
        for (int i = 0; i < 360; i++) { // up to 1h with 10s ticks
            if (manualRequested) return true;
            if (cancelRequested) return false;
            boolean allOk = true;
            for (ComputeId dep : def.deps()) {
                JobStatus s = orch.queryJobStatus(dep.path(), def.group(), def.project(), def.lane(), runDate);
                if (s == JobStatus.FAILED || s == JobStatus.SKIPPED) return false;
                if (s != JobStatus.COMPLETED) { allOk = false; break; }
            }
            if (allOk) return true;
            Workflow.sleep(Duration.ofSeconds(10));
        }
        return false;
    }

    @Override public void manualRun(Map<String, String> args) {
        manualArgs = args == null ? Map.of() : new LinkedHashMap<>(args);
        manualRequested = true;
    }

    @Override public void cancel() { cancelRequested = true; }
    @Override public JobState getState() { return state; }
}
