package com.compute.temporal;

import com.compute.model.LaneRef;

/** Convention for naming task queues. */
public final class TaskQueues {

    private TaskQueues() {}

    /** Default queue for service/job/task-parent workflows in a namespace. */
    public static String forNamespace(LaneRef ref) {
        return ref.temporalNamespace();
    }

    /** Default queue for service/job/task-parent workflows in a namespace string. */
    public static String forNamespace(String namespace) {
        return namespace;
    }

    public static String forSystem() {
        return "vasara-system";
    }

    public static String forTask(String taskId) {
        return "task-" + taskId;
    }
}
