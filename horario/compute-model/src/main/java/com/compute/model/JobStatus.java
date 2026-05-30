package com.compute.model;

public enum JobStatus {
    /** Workflow registered but dep-wait not started yet. */
    SCHEDULED,
    /** Waiting for upstream job dependencies to complete. */
    WAITING_DEPS,
    /** OCP Job created; pod is pending scheduling or running init containers. */
    STARTING,
    /** Main container is executing. */
    RUNNING,
    COMPLETED, FAILED, SKIPPED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == SKIPPED;
    }
}
