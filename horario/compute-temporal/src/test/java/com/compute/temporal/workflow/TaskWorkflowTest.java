package com.compute.temporal.workflow;

import com.compute.model.ComputeId;
import com.compute.model.ImageSpec;
import com.compute.model.Lane;
import com.compute.model.SubtaskRequest;
import com.compute.model.SubtaskResult;
import com.compute.model.TaskRequest;
import com.compute.model.TaskState;
import com.compute.model.TaskStatus;
import com.compute.temporal.activity.ImageActivities;
import com.compute.temporal.activity.OcpActivities;
import com.compute.temporal.activity.SubtaskActivities;
import com.compute.temporal.workflow.impl.SubtaskWorkflowImpl;
import com.compute.temporal.workflow.impl.TaskWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class TaskWorkflowTest {

    private static final String MAIN_QUEUE = "task-main-test-queue";
    private static final String TASK_ID    = "fixed-test-task-id";
    private static final String TASK_QUEUE = "task-" + TASK_ID;

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;

    private final StubImageActivities imageActs   = new StubImageActivities();
    private final StubOcpActivities   ocpActs     = new StubOcpActivities();
    private final StubSubtaskActivities subtaskActs = new StubSubtaskActivities();

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();

        // Main worker: runs TaskWorkflowImpl and orchestration activities
        Worker mainWorker = testEnv.newWorker(MAIN_QUEUE);
        mainWorker.registerWorkflowImplementationTypes(TaskWorkflowImpl.class);
        mainWorker.registerActivitiesImplementations(imageActs, ocpActs);

        // Subtask worker: runs SubtaskWorkflowImpl on the per-task queue
        Worker subtaskWorker = testEnv.newWorker(TASK_QUEUE);
        subtaskWorker.registerWorkflowImplementationTypes(SubtaskWorkflowImpl.class);
        subtaskWorker.registerActivitiesImplementations(subtaskActs);

        testEnv.start();
        client = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private TaskWorkflow stub(String wfId) {
        return client.newWorkflowStub(TaskWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(MAIN_QUEUE)
                        .setWorkflowId(wfId)
                        .build());
    }

    @Test
    void taskWithAllSuccessfulSubtasksCompletesSuccessfully() {
        subtaskActs.returnSuccess = true;

        TaskRequest req = new TaskRequest(
                TASK_ID, "rates", "eod", Lane.DEV, "dev", 2,
                List.of(
                        new SubtaskRequest("s1", "compute", Map.of("k", "v1")),
                        new SubtaskRequest("s2", "compute", Map.of("k", "v2"))));

        TaskWorkflow wf = stub("task-success");
        WorkflowClient.start(wf::run, req);
        client.newUntypedWorkflowStub("task-success").getResult(Void.class);

        TaskState s = client.newWorkflowStub(TaskWorkflow.class, "task-success").getState();
        assertThat(s.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(s.completedSubtasks()).isEqualTo(2);
        assertThat(s.failedSubtasks()).isEqualTo(0);
        assertThat(s.totalSubtasks()).isEqualTo(2);
    }

    @Test
    void taskWithAllFailingSubtasksTransitionsToFailed() {
        subtaskActs.returnSuccess = false;

        TaskRequest req = new TaskRequest(
                TASK_ID, "rates", "eod", Lane.DEV, "dev", 1,
                List.of(new SubtaskRequest("s1", "compute", Map.of())));

        TaskWorkflow wf = stub("task-fail");
        WorkflowClient.start(wf::run, req);
        client.newUntypedWorkflowStub("task-fail").getResult(Void.class);

        TaskState s = client.newWorkflowStub(TaskWorkflow.class, "task-fail").getState();
        assertThat(s.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(s.failedSubtasks()).isEqualTo(1);
    }

    @Test
    void taskWithNoSubtasksCompletesImmediately() {
        TaskRequest req = new TaskRequest(
                TASK_ID, "rates", "eod", Lane.DEV, "dev", 1, List.of());

        TaskWorkflow wf = stub("task-empty");
        WorkflowClient.start(wf::run, req);
        client.newUntypedWorkflowStub("task-empty").getResult(Void.class);

        TaskState s = client.newWorkflowStub(TaskWorkflow.class, "task-empty").getState();
        assertThat(s.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(s.totalSubtasks()).isEqualTo(0);
    }

    @Test
    void taskMetadataIsPreservedInState() {
        TaskRequest req = new TaskRequest(
                TASK_ID, "rates", "eod", Lane.DEV, "dev", 1,
                List.of(new SubtaskRequest("s1", "compute", Map.of())));

        TaskWorkflow wf = stub("task-meta");
        WorkflowClient.start(wf::run, req);
        client.newUntypedWorkflowStub("task-meta").getResult(Void.class);

        TaskState s = client.newWorkflowStub(TaskWorkflow.class, "task-meta").getState();
        assertThat(s.taskId()).isEqualTo(TASK_ID);
        assertThat(s.group()).isEqualTo("rates");
        assertThat(s.project()).isEqualTo("eod");
        assertThat(s.lane()).isEqualTo(Lane.DEV);
        assertThat(s.startTime()).isNotNull();
        assertThat(s.endTime()).isNotNull();
    }

    // ── activity stubs ─────────────────────────────────────────────────────────

    static class StubImageActivities implements ImageActivities {
        @Override
        public List<ImageSpec> fetch(String group, String project, Lane lane, String version) {
            return List.of(new ImageSpec("core", "r/core:1", 0));
        }
    }

    static class StubOcpActivities implements OcpActivities {
        @Override
        public DeployResult applyService(com.compute.model.ServiceDefinition def, List<ImageSpec> images) {
            return new DeployResult("d", "vasara");
        }

        @Override
        public ReplicaStatus serviceReadyStatus(ComputeId id, Lane lane, String group, String project) {
            return new ReplicaStatus(1, 1, true, false);
        }

        @Override
        public void scaleService(ComputeId id, Lane lane, String group, String project, int replicas) {}

        @Override
        public void deleteService(ComputeId id, Lane lane, String group, String project) {}

        @Override
        public JobResult applyOcpJob(com.compute.model.JobDefinition def, List<ImageSpec> images,
                                     LocalDate runDate, Map<String, String> args) {
            return new JobResult("job-1", "vasara");
        }

        @Override
        public JobPoll pollOcpJob(String jobName) { return JobPoll.SUCCEEDED; }

        @Override
        public void deleteOcpJob(String jobName) {}

        @Override
        public JobResult applyTaskWorkers(com.compute.model.TaskRequest task, List<ImageSpec> images,
                                          String temporalTarget, String temporalNamespace) {
            return new JobResult("workers-" + task.taskId(), "vasara");
        }
    }

    static class StubSubtaskActivities implements SubtaskActivities {
        volatile boolean returnSuccess = true;

        @Override
        public SubtaskResult execute(SubtaskRequest req) {
            return returnSuccess ? SubtaskResult.ok(req.subtaskId(), Map.of())
                                 : SubtaskResult.failed(req.subtaskId(), "forced failure");
        }
    }
}
