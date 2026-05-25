package com.example.mapreduce.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface MapReduceWorkflow {

    @WorkflowMethod
    long run(int totalTasks, int batchSize);

    /** Returns how many batches have completed so far. */
    @QueryMethod
    int getCompletedBatches();
}
