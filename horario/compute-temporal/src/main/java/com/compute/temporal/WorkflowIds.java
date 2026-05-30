package com.compute.temporal;

import com.compute.model.ComputeId;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Centralised workflow-ID convention. */
public final class WorkflowIds {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private WorkflowIds() {}

    public static String service(ComputeId id) {
        return "service:" + id.path();
    }

    public static String job(ComputeId id, LocalDate date) {
        return "job:" + id.path() + ":" + date.format(ISO);
    }

    public static String task(String taskId) {
        return "task:" + taskId;
    }

    public static String subtask(String taskId, String subtaskId) {
        return "task:" + taskId + ":" + subtaskId;
    }

    public static String scheduler() {
        return "scheduler:global";
    }

    /**
     * Fixed workflow ID for a scheduler {@code JobWorkflow} (no date suffix).
     * The Temporal Schedule fires at most one concurrent execution via SKIP overlap policy.
     * Pattern: {@code "job:{path}"} — same prefix as regular jobs, no trailing {@code :date}.
     */
    public static String schedulerJob(ComputeId id) {
        return "job:" + id.path();
    }

    /**
     * Temporal Schedule ID for a scheduler job.
     * Lives in the project's Temporal namespace.
     * Pattern: {@code "sched:{path}"}.
     */
    public static String scheduleId(ComputeId id) {
        return "sched:" + id.path();
    }
}
