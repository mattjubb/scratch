package io.acme.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.List;

/**
 * A job runs to completion.
 * <p>
 * A job fires when:
 * <ol>
 *   <li>its {@link Schedule} says it is due, AND</li>
 *   <li>every dependency in {@link #dependencies()} has a successful run whose
 *       finish time falls within the job's current scheduling window.</li>
 * </ol>
 * A job may also fire purely as a downstream consequence of an upstream
 * completion, if its schedule is {@link Schedule.Manual} and the DAG upstream
 * completed — useful for ETL-style fan-out.
 */
public record JobDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("schedule") Schedule schedule,
        @JsonProperty("dependencies") List<String> dependencies,
        @JsonProperty("runtime") RuntimeSpec runtime,
        @JsonProperty("activeDeadline") Duration activeDeadline,
        @JsonProperty("backoffLimit") Integer backoffLimit,
        @JsonProperty("schedulingWindow") Duration schedulingWindow
) {
    @JsonCreator
    public JobDefinition {
        if (name == null || !name.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException(
                    "name must be a valid DNS-1123 label: " + name);
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace required");
        }
        if (schedule == null) schedule = new Schedule.Manual();
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        if (runtime == null) throw new IllegalArgumentException("runtime required");
        if (activeDeadline == null) activeDeadline = Duration.ofHours(1);
        if (backoffLimit == null) backoffLimit = 0;
        // Default slightly longer than 1 day so a daily job whose parent ran
        // slow yesterday still satisfies today's dependency check.
        if (schedulingWindow == null) schedulingWindow = Duration.ofHours(25);
    }
}
