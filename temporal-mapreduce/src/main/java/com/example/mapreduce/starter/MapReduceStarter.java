package com.example.mapreduce.starter;

import com.example.mapreduce.worker.MapReduceWorker;
import com.example.mapreduce.workflow.MapReduceWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;

import java.util.UUID;

/**
 * Submits the map-reduce workflow and listens for completion via progress queries.
 *
 * Expected output:
 *   Progress [====================] 100/100 batches
 *   Result: 333283335000
 */
public class MapReduceStarter {

    static final int TOTAL_TASKS = 10_000;
    static final int BATCH_SIZE  = 100;
    static final int NUM_BATCHES = TOTAL_TASKS / BATCH_SIZE;

    public static void main(String[] args) throws InterruptedException {
        WorkflowServiceStubs service = WorkflowServiceStubs.
        WorkflowClient client = WorkflowClient.newInstance(service);

        String workflowId = "map-reduce-" + UUID.randomUUID();

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(MapReduceWorker.TASK_QUEUE)
                .setWorkflowId(workflowId)
                .build();

        MapReduceWorkflow stub = client.newWorkflowStub(MapReduceWorkflow.class, options);

        // Submit the workflow without blocking — returns as soon as Temporal accepts it.
        WorkflowClient.start(stub::run, TOTAL_TASKS, BATCH_SIZE);
        System.out.println("Workflow submitted: " + workflowId);
        System.out.println("Waiting for " + NUM_BATCHES + " batches × " + BATCH_SIZE + " tasks = " + TOTAL_TASKS + " total tasks...\n");

        // Poll progress via query until all batches are done.
        int lastPrinted = -1;
        while (true) {
            int done = stub.getCompletedBatches();
            if (done != lastPrinted) {
                printProgress(done, NUM_BATCHES);
                lastPrinted = done;
            }
            if (done >= NUM_BATCHES) break;
            Thread.sleep(300);
        }

        // Workflow is complete; fetch the result (non-blocking since work is done).
        long result = WorkflowStub.fromTyped(stub).getResult(Long.class);
        System.out.println("\nResult: " + result);
        System.out.println("Expected: 333283335000");

        service.shutdownNow();
    }

    private static void printProgress(int done, int total) {
        int barWidth = 40;
        int filled = (int) ((double) done / total * barWidth);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barWidth; i++) bar.append(i < filled ? '=' : ' ');
        bar.append(']');
        System.out.printf("\rProgress %s %d/%d batches", bar, done, total);
        System.out.flush();
    }
}
