package com.compute.temporal.workflow.impl;

import com.compute.model.SubtaskRequest;
import com.compute.model.SubtaskResult;
import com.compute.temporal.activity.SubtaskActivities;
import com.compute.temporal.workflow.SubtaskWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public final class SubtaskWorkflowImpl implements SubtaskWorkflow {

    private final SubtaskActivities act = Workflow.newActivityStub(
            SubtaskActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    @Override
    public SubtaskResult run(SubtaskRequest request) {
        return act.execute(request);
    }
}
