package com.example.mapreduce.workflow;

import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.util.ArrayList;
import java.util.List;

public class MapReduceWorkflowImpl implements MapReduceWorkflow {

    private int completedBatches = 0;

    @Override
    public long run(int totalTasks, int batchSize) {
        int numBatches = totalTasks / batchSize;

        // Fan out all child batch workflows in parallel.
        List<Promise<Long>> batchPromises = new ArrayList<>(numBatches);
        for (int b = 0; b < numBatches; b++) {
            int startIndex = b * batchSize;
            BatchWorkflow child = Workflow.newChildWorkflowStub(BatchWorkflow.class);
            batchPromises.add(Async.function(child::processBatch, startIndex, batchSize));
        }

        // Collect results in order; each resolved promise increments the query counter.
        long total = 0;
        for (Promise<Long> p : batchPromises) {
            total += p.get();
            completedBatches++;
        }
        return total;
    }

    @Override
    public int getCompletedBatches() {
        return completedBatches;
    }
}
