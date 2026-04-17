package io.acme.orchestrator.dag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * On-disk representation of a PENDING_DEPS run.
 * <p>
 * We don't store the full {@link RunStatus} — phase is implicit (it's always
 * PENDING_DEPS while the CM exists) and finishedAt is meaningless for a
 * pending run. {@code createdAt} is recorded so cold-start recovery can
 * discard stale intents that would never fire (e.g. a parent has been
 * broken for a week).
 */
public record PendingRunRecord(
        @JsonProperty("logicalName") String logicalName,
        @JsonProperty("runId") String runId,
        @JsonProperty("fireTime") Instant fireTime,
        @JsonProperty("createdAt") Instant createdAt
) {
    @JsonCreator
    public PendingRunRecord {
        if (logicalName == null || runId == null || fireTime == null) {
            throw new IllegalArgumentException("logicalName, runId, fireTime required");
        }
        if (createdAt == null) createdAt = Instant.now();
    }
}
