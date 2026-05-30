package com.compute.temporal.workflow;

import com.compute.model.TaskRequest;
import com.compute.model.TaskState;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Parent workflow for an on-demand task. First provisions an ephemeral OCP Job whose
 * worker pods poll task-queue {@code task-{taskId}}, then dispatches each subtask as a
 * child workflow on that queue, and finally cleans up the worker Job.
 */
@WorkflowInterface
public interface TaskWorkflow {

    @WorkflowMethod
    void run(TaskRequest request);

    @SignalMethod
    void cancel();

    @QueryMethod
    TaskState getState();
}
