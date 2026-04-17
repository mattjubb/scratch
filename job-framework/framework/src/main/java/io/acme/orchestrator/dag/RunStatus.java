package io.acme.orchestrator.dag;

import java.time.Instant;

/**
 * Status of the most recent firing of a logical job.
 * Indexed by logical job name in the {@link DependencyEngine}.
 */
public record RunStatus(
        String logicalName,
        String runId,
        Instant fireTime,
        Instant finishedAt,
        Phase phase
) {
    public enum Phase { PENDING_DEPS, SUBMITTED, RUNNING, SUCCEEDED, FAILED }

    public boolean isTerminal() {
        return phase == Phase.SUCCEEDED || phase == Phase.FAILED;
    }
}
