package com.example.mapreduce.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface BatchWorkflow {

    @WorkflowMethod
    long processBatch(int startIndex, int batchSize);
}
