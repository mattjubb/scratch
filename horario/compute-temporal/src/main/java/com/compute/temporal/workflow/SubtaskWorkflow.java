package com.compute.temporal.workflow;

import com.compute.model.SubtaskRequest;
import com.compute.model.SubtaskResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Child workflow representing one subtask. Runs on a {@code task-{taskId}} queue
 * polled exclusively by the ephemeral workers spun up by the parent {@link TaskWorkflow}.
 */
@WorkflowInterface
public interface SubtaskWorkflow {

    @WorkflowMethod
    SubtaskResult run(SubtaskRequest request);
}
