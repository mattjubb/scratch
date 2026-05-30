package com.compute.model;

import java.time.Instant;
import java.util.List;

public record TaskState(
        String taskId,
        String group,
        String project,
        Lane lane,
        String version,
        TaskStatus status,
        int totalSubtasks,
        int completedSubtasks,
        int failedSubtasks,
        int runningSubtasks,
        Instant startTime,
        Instant endTime,
        String workerJobName,
        List<SubtaskStateSummary> subtasks
) {
    public record SubtaskStateSummary(
            String subtaskId,
            TaskStatus status,
            Instant startTime,
            Instant endTime,
            String message
    ) {}
}
