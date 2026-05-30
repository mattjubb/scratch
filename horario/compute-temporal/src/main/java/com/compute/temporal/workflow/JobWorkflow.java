package com.compute.temporal.workflow;

import com.compute.model.JobDefinition;
import com.compute.model.JobState;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.LocalDate;
import java.util.Map;

/**
 * Runs a single scheduled job for a specific {@code runDate}. One workflow execution
 * per job per day. A {@code manualRun} signal force-starts (or restarts) the underlying
 * OCP Job with the provided args, bypassing dependency waits.
 */
@WorkflowInterface
public interface JobWorkflow {

    @WorkflowMethod
    void run(JobDefinition definition, LocalDate runDate);

    @SignalMethod
    void manualRun(Map<String, String> args);

    @SignalMethod
    void cancel();

    @QueryMethod
    JobState getState();
}
