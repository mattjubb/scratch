package com.compute.model;

import java.util.Objects;

/**
 * The (group, project, lane) triple that addresses a Temporal namespace,
 * image-version key, and OCP label set.
 */
public record LaneRef(String group, String project, Lane lane) {

    public LaneRef {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(lane, "lane");
    }

    /** Temporal namespace name, e.g. {@code rates-swaps-dev}. */
    public String temporalNamespace() {
        return group + "-" + project + "-" + lane.code();
    }
}
