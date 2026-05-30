package com.compute.model;

import java.util.List;
import java.util.UUID;

/**
 * On-demand task submitted by a client. The control plane records the assigned
 * {@code taskId} and starts a {@code TaskWorkflow} in the namespace identified by
 * {@code (group, project, lane)}. The first activity provisions an OCP Job sized to
 * {@code parallelism} that runs Temporal workers polling task-queue {@code task-{taskId}}.
 *
 * @param taskId       assigned UUID
 * @param group        group label
 * @param project      project label
 * @param lane         target lane
 * @param version      ImageVersionAPI version key (controls worker image)
 * @param parallelism  number of worker pods in the ephemeral OCP Job
 * @param subtasks     list of subtask payloads
 */
public record TaskRequest(
        String taskId,
        String group,
        String project,
        Lane lane,
        String version,
        int parallelism,
        List<SubtaskRequest> subtasks
) {
    public static String newTaskId() {
        return UUID.randomUUID().toString();
    }

    public LaneRef laneRef() {
        return new LaneRef(group, project, lane);
    }

    public String taskQueue() {
        return "task-" + taskId;
    }
}
