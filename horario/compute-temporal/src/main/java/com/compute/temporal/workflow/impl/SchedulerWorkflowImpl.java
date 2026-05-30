package com.compute.temporal.workflow.impl;

import com.compute.model.ComputeId;
import com.compute.model.JobDefinition;
import com.compute.model.ServiceDefinition;
import com.compute.temporal.activity.DefinitionActivities;
import com.compute.temporal.activity.OrchestrationActivities;
import com.compute.temporal.workflow.SchedulerReport;
import com.compute.temporal.workflow.SchedulerWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SchedulerWorkflowImpl implements SchedulerWorkflow {

    private final DefinitionActivities defs = Workflow.newActivityStub(
            DefinitionActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    private final OrchestrationActivities orch = Workflow.newActivityStub(
            OrchestrationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    private boolean reloadFlag;

    @Override
    public SchedulerReport reconcile() {
        List<String> started = new ArrayList<>();
        List<String> stopped = new ArrayList<>();
        List<String> redeployed = new ArrayList<>();
        List<String> jobsScheduled = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        DefinitionActivities.Loaded loaded = defs.loadAll();

        // 1) Services — start new, stop removed (per group/project/lane triple)
        Map<TripleKey, List<ServiceDefinition>> svcByTriple = new LinkedHashMap<>();
        for (ServiceDefinition s : loaded.services()) {
            svcByTriple.computeIfAbsent(
                    new TripleKey(s.group(), s.project(), s.lane().code()),
                    k -> new ArrayList<>()).add(s);
        }

        for (Map.Entry<TripleKey, List<ServiceDefinition>> e : svcByTriple.entrySet()) {
            TripleKey k = e.getKey();
            List<ServiceDefinition> yaml = e.getValue();
            Set<String> yamlIds = new HashSet<>();
            for (ServiceDefinition s : yaml) yamlIds.add(s.id().path());

            List<String> running = orch.listRunningServiceIds(k.group(), k.project(), parseLane(k.lane()));
            Set<String> runningIds = new HashSet<>(running);

            for (ServiceDefinition s : yaml) {
                // ensureServiceWorkflow handles both cases:
                //   - workflow NOT running → starts a new ServiceWorkflow execution
                //   - workflow already running → sends redeploy(def) signal so it picks up
                //     definition changes and re-applies the OCP Deployment (idempotent via
                //     server-side apply, so re-deploying an unchanged service is harmless).
                orch.ensureServiceWorkflow(s);
                if (runningIds.contains(s.id().path())) {
                    redeployed.add(s.id().path());
                } else {
                    started.add(s.id().path());
                }
            }
            for (String runId : running) {
                if (!yamlIds.contains(runId)) {
                    orch.stopServiceWorkflow(runId, k.group(), k.project(), parseLane(k.lane()));
                    stopped.add(runId);
                }
            }
        }

        // 2) Jobs — schedule today's run for each YAML job
        LocalDate today = LocalDate.ofInstant(
                Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
        loaded.jobs().sort(Comparator.comparing(j -> j.id().path()));
        for (JobDefinition j : loaded.jobs()) {
            try {
                orch.scheduleJobWorkflow(j, today);
                jobsScheduled.add(j.id().path());
            } catch (Exception ex) {
                warnings.add("schedule failed for " + j.id().path() + ": " + ex.getMessage());
            }
        }

        // Drain any pending reload signal without restarting reconciliation (one pass per run)
        reloadFlag = false;
        return new SchedulerReport(started, stopped, redeployed, jobsScheduled, warnings);
    }

    @Override
    public void reload() {
        reloadFlag = true;
    }

    private static com.compute.model.Lane parseLane(String code) {
        return com.compute.model.Lane.fromCode(code);
    }

    /** Helper to keep ComputeId import meaningful (used implicitly via JobDefinition). */
    @SuppressWarnings("unused")
    private ComputeId unused(JobDefinition j) { return j.id(); }

    private record TripleKey(String group, String project, String lane) {}
}
