package com.compute.temporal.workflow;

import com.compute.model.ServiceDefinition;
import com.compute.model.ServiceState;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Lifecycle workflow for a single service definition. Runs continuously until removed
 * from YAML (a {@code stop} signal exits the workflow). Signals can flip the desired
 * state at any time; {@link #getState()} returns the typed snapshot the control plane
 * projects to the UI.
 */
@WorkflowInterface
public interface ServiceWorkflow {

    @WorkflowMethod
    void run(ServiceDefinition definition);

    @SignalMethod
    void start();

    @SignalMethod
    void stop();

    @SignalMethod
    void restart();

    @SignalMethod
    void ice();

    /** Notify the workflow that its YAML definition changed; trigger a rolling redeploy. */
    @SignalMethod
    void redeploy(ServiceDefinition newDefinition);

    @QueryMethod
    ServiceState getState();
}
