package com.compute.temporal.workflow;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Reconciler workflow run on a daily Temporal Schedule (and signalled on-demand via
 * {@link #reload}). Reads the YAML definitions tree, starts {@code ServiceWorkflow}s
 * for newly-added services, signals {@code stop} on removed ones, and creates today's
 * {@code JobWorkflow} executions.
 *
 * <p>This workflow does <em>not</em> create task workflows — tasks are created on
 * demand by the REST control plane.</p>
 */
@WorkflowInterface
public interface SchedulerWorkflow {

    /**
     * One reconciliation pass. The Temporal Schedule starts a fresh execution each
     * day; the workflow exits once reconciliation completes.
     */
    @WorkflowMethod
    SchedulerReport reconcile();

    /** Trigger an immediate re-read of the YAML tree. */
    @SignalMethod
    void reload();
}
