package com.compute.temporal.activity;

import com.compute.model.SubtaskRequest;
import com.compute.model.SubtaskResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The activity invoked by {@code SubtaskWorkflowImpl}. Implementations live in the
 * user's image and are registered by the ephemeral task worker via SPI (see
 * {@code compute-subtask-worker}).
 */
@ActivityInterface
public interface SubtaskActivities {

    @ActivityMethod
    SubtaskResult execute(SubtaskRequest request);
}
