package com.example.mapreduce.workflow;

import com.example.mapreduce.activity.MapActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class BatchWorkflowImpl implements BatchWorkflow {

    private final MapActivity mapActivity = Workflow.newActivityStub(
            MapActivity.class,
            ActivityOptions.newBuilder()
                    .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                    .build()
    );

    @Override
    public long processBatch(int startIndex, int batchSize) {
        // Fan out all activities in this batch in parallel.
        List<Promise<Long>> promises = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            int taskIndex = startIndex + i;
            promises.add(Async.function(mapActivity::processTask, taskIndex));
        }

        long batchSum = 0;
        for (Promise<Long> p : promises) {
            batchSum += p.get();
        }
        return batchSum;
    }
}
