package com.compute.temporal.workflow.impl;

import com.compute.model.ImageSpec;
import com.compute.model.SubtaskRequest;
import com.compute.model.SubtaskResult;
import com.compute.model.TaskRequest;
import com.compute.model.TaskState;
import com.compute.model.TaskStatus;
import com.compute.temporal.activity.ImageActivities;
import com.compute.temporal.activity.OcpActivities;
import com.compute.temporal.workflow.SubtaskWorkflow;
import com.compute.temporal.workflow.TaskWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class TaskWorkflowImpl implements TaskWorkflow {

    private final ImageActivities images = Workflow.newActivityStub(
            ImageActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
                    .build());

    private final OcpActivities ocp = Workflow.newActivityStub(
            OcpActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    private TaskRequest req;
    private TaskState state;
    private boolean cancelRequested;

    @Override
    public void run(TaskRequest request) {
        this.req = request;
        this.state = new TaskState(
                request.taskId(), request.group(), request.project(), request.lane(),
                request.version(), TaskStatus.PENDING,
                request.subtasks().size(), 0, 0, 0,
                Instant.ofEpochMilli(Workflow.currentTimeMillis()), null, "",
                List.of());

        // Provision ephemeral worker job
        state = withStatus(TaskStatus.PROVISIONING_WORKERS);
        List<ImageSpec> imgs = images.fetch(req.group(), req.project(), req.lane(), req.version());
        String temporalTarget = System.getenv().getOrDefault("TEMPORAL_TARGET", "127.0.0.1:7233");
        OcpActivities.JobResult applied = ocp.applyTaskWorkers(req, imgs, temporalTarget, req.laneRef().temporalNamespace());

        // Fire-and-await all subtasks on the per-task queue
        state = withStatus(TaskStatus.RUNNING);
        ChildWorkflowOptions childOpts = ChildWorkflowOptions.newBuilder()
                .setTaskQueue(req.taskQueue())
                .setWorkflowExecutionTimeout(Duration.ofHours(6))
                .build();

        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();

        List<Promise<SubtaskResult>> promises = new ArrayList<>(req.subtasks().size());
        for (SubtaskRequest sub : req.subtasks()) {
            SubtaskWorkflow child = Workflow.newChildWorkflowStub(SubtaskWorkflow.class, childOpts);
            running.incrementAndGet();
            Promise<SubtaskResult> p = Async.function(child::run, sub).thenApply(r -> {
                running.decrementAndGet();
                if (r != null && r.success()) completed.incrementAndGet();
                else failed.incrementAndGet();
                return r;
            });
            promises.add(p);
        }
        state = withCounts(running.get(), completed.get(), failed.get());

        // Wait for all (with periodic status refresh + cancel check)
        Promise.allOf(promises).get();

        if (cancelRequested) {
            ocp.deleteOcpJob(applied.jobName());
            state = withStatus(TaskStatus.FAILED);
            return;
        }

        ocp.deleteOcpJob(applied.jobName());
        state = withCounts(0, completed.get(), failed.get());
        state = withStatus(failed.get() == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED);
    }

    private TaskState withStatus(TaskStatus s) {
        boolean terminal = s.isTerminal();
        return new TaskState(
                state.taskId(), state.group(), state.project(), state.lane(), state.version(), s,
                state.totalSubtasks(), state.completedSubtasks(), state.failedSubtasks(), state.runningSubtasks(),
                state.startTime(),
                terminal ? Instant.ofEpochMilli(Workflow.currentTimeMillis()) : state.endTime(),
                state.workerJobName(), state.subtasks());
    }

    private TaskState withCounts(int running, int completed, int failed) {
        return new TaskState(
                state.taskId(), state.group(), state.project(), state.lane(), state.version(), state.status(),
                state.totalSubtasks(), completed, failed, running,
                state.startTime(), state.endTime(),
                state.workerJobName(), state.subtasks());
    }

    @Override public void cancel() { cancelRequested = true; }
    @Override public TaskState getState() { return state; }
}
