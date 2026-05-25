package com.example.mapreduce.worker;

import com.example.mapreduce.activity.MapActivityImpl;
import com.example.mapreduce.workflow.BatchWorkflowImpl;
import com.example.mapreduce.workflow.MapReduceWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/**
 * Run this first. Prerequisites:
 *   1. Install Temporal CLI  (choco install temporal-cli  OR  brew install temporal)
 *   2. temporal server start-dev   (UI: http://localhost:8233)
 *   3. Start this worker
 *   4. In another terminal, run MapReduceStarter
 */
public class MapReduceWorker {

    public static final String TASK_QUEUE = "MAP_REDUCE_QUEUE";

    public static void main(String[] args) {
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        Worker worker = factory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(MapReduceWorkflowImpl.class, BatchWorkflowImpl.class);
        worker.registerActivitiesImplementations(new MapActivityImpl());

        System.out.println("Worker started on task queue: " + TASK_QUEUE);
        factory.start();
    }
}
