package com.compute.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public record JobState(
        ComputeId id,
        Lane lane,
        String group,
        String project,
        LocalDate runDate,
        JobStatus status,
        Instant scheduledTime,
        Instant startTime,
        Instant endTime,
        int exitCode,
        String message,
        Map<String, String> args,
        boolean manuallyTriggered
) {
    public JobState withStatus(JobStatus next, String message) {
        return new JobState(
                id, lane, group, project, runDate, next,
                scheduledTime,
                (next == JobStatus.STARTING || next == JobStatus.RUNNING) && startTime == null
                        ? Instant.now() : startTime,
                next.isTerminal() ? Instant.now() : endTime,
                exitCode, message, args, manuallyTriggered
        );
    }

    public JobState withExit(int code, String message) {
        return new JobState(
                id, lane, group, project, runDate, status,
                scheduledTime, startTime, endTime, code, message, args, manuallyTriggered
        );
    }
}
