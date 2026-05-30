package com.compute.model;

import java.util.List;
import java.util.Map;

/**
 * A scheduled job. {@code schedule} is a 5-field cron expression interpreted in UTC.
 * {@code deps} are job IDs (slash-prefixed paths) whose successful completion is
 * required before this job will run automatically; manual runs bypass deps.
 *
 * <p>When {@code scheduler} is {@code true} this definition describes a
 * <em>project scheduler job</em>: instead of spinning up an OCP container, the
 * {@code JobWorkflow} calls {@code reconcileProject} to start/stop services and
 * pre-populate sibling job workflows for the next {@link #lookaheadDays} days.
 * Scheduler jobs do not require a {@code mainClass} or {@code version}.
 */
public record JobDefinition(
        ComputeId id,
        Lane lane,
        String group,
        String project,
        String mainClass,
        String version,
        String schedule,
        List<ComputeId> deps,
        ResourceSpec resources,
        List<EnvVar> env,
        Map<String, String> defaultArgs,
        boolean scheduler,
        int lookaheadDays
) {
    public LaneRef laneRef() {
        return new LaneRef(group, project, lane);
    }
}
