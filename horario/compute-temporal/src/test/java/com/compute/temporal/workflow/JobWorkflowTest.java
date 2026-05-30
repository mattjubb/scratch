package com.compute.temporal.workflow;

import com.compute.model.ComputeId;
import com.compute.model.ImageSpec;
import com.compute.model.JobDefinition;
import com.compute.model.JobState;
import com.compute.model.JobStatus;
import com.compute.model.Lane;
import com.compute.model.ResourceSpec;
import com.compute.temporal.activity.ImageActivities;
import com.compute.temporal.activity.OcpActivities;
import com.compute.temporal.activity.OrchestrationActivities;
import com.compute.temporal.workflow.impl.JobWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class JobWorkflowTest {

    private static final String QUEUE = "job-test-queue";

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;

    // Configurable stubs — set fields before starting the workflow.
    private final StubImageActivities imageActs   = new StubImageActivities();
    private final StubOcpActivities   ocpActs     = new StubOcpActivities();
    private final StubOrchActivities  orchActs    = new StubOrchActivities();

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(JobWorkflowImpl.class);
        worker.registerActivitiesImplementations(imageActs, ocpActs, orchActs);
        testEnv.start();
        client = testEnv.getWorkflowClient();
        // reset stubs to defaults
        imageActs.reset();
        ocpActs.reset();
        orchActs.reset();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static JobDefinition schedulerDef() {
        return new JobDefinition(
                ComputeId.of("/rates/eod/scheduler"), Lane.DEV, "rates", "eod",
                "", "dev", "0 0 * * *", List.of(),
                ResourceSpec.defaults(), List.of(), Map.of(), true, 2);
    }

    private static JobDefinition regularDef() {
        return new JobDefinition(
                ComputeId.of("/rates/eod/curve-build"), Lane.DEV, "rates", "eod",
                "com.example.CurveBuildJob", "dev", "0 17 * * *", List.of(),
                ResourceSpec.defaults(), List.of(), Map.of(), false, 2);
    }

    private static JobDefinition defWithDep(ComputeId dep) {
        return new JobDefinition(
                ComputeId.of("/rates/eod/curve-build"), Lane.DEV, "rates", "eod",
                "com.example.CurveBuildJob", "dev", "0 17 * * *", List.of(dep),
                ResourceSpec.defaults(), List.of(), Map.of(), false, 2);
    }

    private JobWorkflow stub(String wfId) {
        return client.newWorkflowStub(JobWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(QUEUE)
                        .setWorkflowId(wfId)
                        .build());
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    void schedulerJobCallsReconcileAndCompletes() {
        JobWorkflow wf = stub("sched-ok");
        WorkflowClient.start(wf::run, schedulerDef(), (LocalDate) null);
        client.newUntypedWorkflowStub("sched-ok").getResult(Void.class);

        assertThat(orchActs.reconcileCalled.get()).isTrue();
        JobState s = client.newWorkflowStub(JobWorkflow.class, "sched-ok").getState();
        assertThat(s.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(s.exitCode()).isEqualTo(0);
    }

    @Test
    void schedulerJobWithNullRunDateDerivesDate() {
        JobWorkflow wf = stub("sched-null-date");
        WorkflowClient.start(wf::run, schedulerDef(), (LocalDate) null);
        client.newUntypedWorkflowStub("sched-null-date").getResult(Void.class);

        JobState s = client.newWorkflowStub(JobWorkflow.class, "sched-null-date").getState();
        assertThat(s.runDate()).isNotNull();
    }

    @Test
    void schedulerJobTransitionsToFailedWhenReconcileThrows() {
        orchActs.reconcileThrows = true;

        JobWorkflow wf = stub("sched-fail");
        WorkflowClient.start(wf::run, schedulerDef(), (LocalDate) null);
        client.newUntypedWorkflowStub("sched-fail").getResult(Void.class);

        JobState s = client.newWorkflowStub(JobWorkflow.class, "sched-fail").getState();
        assertThat(s.status()).isEqualTo(JobStatus.FAILED);
        assertThat(s.exitCode()).isEqualTo(1);
    }

    @Test
    void regularJobSucceedsWhenOcpJobSucceeds() {
        ocpActs.pollResult = OcpActivities.JobPoll.SUCCEEDED;

        LocalDate today = LocalDate.now();
        JobWorkflow wf = stub("job-ok");
        WorkflowClient.start(wf::run, regularDef(), today);
        client.newUntypedWorkflowStub("job-ok").getResult(Void.class);

        JobState s = client.newWorkflowStub(JobWorkflow.class, "job-ok").getState();
        assertThat(s.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(s.exitCode()).isEqualTo(0);
    }

    @Test
    void regularJobFailsWhenOcpJobFails() {
        ocpActs.pollResult = OcpActivities.JobPoll.FAILED;

        LocalDate today = LocalDate.now();
        JobWorkflow wf = stub("job-ocp-fail");
        WorkflowClient.start(wf::run, regularDef(), today);
        client.newUntypedWorkflowStub("job-ocp-fail").getResult(Void.class);

        JobState s = client.newWorkflowStub(JobWorkflow.class, "job-ocp-fail").getState();
        assertThat(s.status()).isEqualTo(JobStatus.FAILED);
        assertThat(s.exitCode()).isEqualTo(1);
    }

    @Test
    void regularJobSkipsWhenDepFailed() {
        orchActs.depStatus = JobStatus.FAILED;

        ComputeId dep = ComputeId.of("/rates/eod/market-data");
        LocalDate today = LocalDate.now();
        JobWorkflow wf = stub("job-dep-fail");
        WorkflowClient.start(wf::run, defWithDep(dep), today);
        client.newUntypedWorkflowStub("job-dep-fail").getResult(Void.class);

        JobState s = client.newWorkflowStub(JobWorkflow.class, "job-dep-fail").getState();
        assertThat(s.status()).isEqualTo(JobStatus.SKIPPED);
    }

    @Test
    void regularJobRunsWhenDepsComplete() {
        orchActs.depStatus = JobStatus.COMPLETED;
        ocpActs.pollResult = OcpActivities.JobPoll.SUCCEEDED;

        ComputeId dep = ComputeId.of("/rates/eod/market-data");
        LocalDate today = LocalDate.now();
        JobWorkflow wf = stub("job-dep-ok");
        WorkflowClient.start(wf::run, defWithDep(dep), today);
        client.newUntypedWorkflowStub("job-dep-ok").getResult(Void.class);

        JobState s = client.newWorkflowStub(JobWorkflow.class, "job-dep-ok").getState();
        assertThat(s.status()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void manualRunBypasses() {
        // dep will never complete on its own — manualRun signal bypasses it
        orchActs.depStatus = JobStatus.SCHEDULED;
        ocpActs.pollResult = OcpActivities.JobPoll.SUCCEEDED;

        ComputeId dep = ComputeId.of("/rates/eod/market-data");
        LocalDate today = LocalDate.now();
        JobWorkflow wf = stub("job-manual");
        WorkflowClient.start(wf::run, defWithDep(dep), today);

        testEnv.sleep(java.time.Duration.ofSeconds(1));
        client.newWorkflowStub(JobWorkflow.class, "job-manual").manualRun(Map.of("override", "yes"));

        client.newUntypedWorkflowStub("job-manual").getResult(Void.class);
        JobState s = client.newWorkflowStub(JobWorkflow.class, "job-manual").getState();
        assertThat(s.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(s.manuallyTriggered()).isTrue();
    }

    @Test
    void cancelSignalSkipsJob() {
        // dep never completes
        orchActs.depStatus = JobStatus.SCHEDULED;

        ComputeId dep = ComputeId.of("/rates/eod/market-data");
        LocalDate today = LocalDate.now();
        JobWorkflow wf = stub("job-cancel");
        WorkflowClient.start(wf::run, defWithDep(dep), today);

        testEnv.sleep(java.time.Duration.ofSeconds(1));
        client.newWorkflowStub(JobWorkflow.class, "job-cancel").cancel();

        client.newUntypedWorkflowStub("job-cancel").getResult(Void.class);
        JobState s = client.newWorkflowStub(JobWorkflow.class, "job-cancel").getState();
        assertThat(s.status()).isEqualTo(JobStatus.SKIPPED);
    }

    // ── activity stubs ─────────────────────────────────────────────────────────

    static class StubImageActivities implements ImageActivities {
        List<ImageSpec> images = List.of(new ImageSpec("core", "r/core:1", 0));

        void reset() {
            images = List.of(new ImageSpec("core", "r/core:1", 0));
        }

        @Override
        public List<ImageSpec> fetch(String group, String project, Lane lane, String version) {
            return images;
        }
    }

    static class StubOcpActivities implements OcpActivities {
        volatile OcpActivities.JobPoll pollResult = OcpActivities.JobPoll.SUCCEEDED;
        String jobName = "test-job-123";

        void reset() {
            pollResult = OcpActivities.JobPoll.SUCCEEDED;
            jobName = "test-job-123";
        }

        @Override
        public OcpActivities.DeployResult applyService(
                com.compute.model.ServiceDefinition def, List<ImageSpec> images) {
            return new OcpActivities.DeployResult("deploy-" + def.id().name(), "vasara");
        }

        @Override
        public OcpActivities.ReplicaStatus serviceReadyStatus(
                ComputeId id, Lane lane, String group, String project) {
            return new OcpActivities.ReplicaStatus(1, 1, true, false);
        }

        @Override
        public void scaleService(ComputeId id, Lane lane, String group, String project, int replicas) {}

        @Override
        public void deleteService(ComputeId id, Lane lane, String group, String project) {}

        @Override
        public OcpActivities.JobResult applyOcpJob(
                JobDefinition def, List<ImageSpec> images,
                LocalDate runDate, Map<String, String> args) {
            return new OcpActivities.JobResult(jobName, "vasara");
        }

        @Override
        public OcpActivities.JobPoll pollOcpJob(String name) {
            return pollResult;
        }

        @Override
        public void deleteOcpJob(String name) {}

        @Override
        public OcpActivities.JobResult applyTaskWorkers(
                com.compute.model.TaskRequest task, List<ImageSpec> images,
                String temporalTarget, String temporalNamespace) {
            return new OcpActivities.JobResult("task-workers-" + task.taskId(), "vasara");
        }
    }

    static class StubOrchActivities implements OrchestrationActivities {
        volatile boolean reconcileThrows = false;
        volatile JobStatus depStatus = JobStatus.COMPLETED;
        final AtomicBoolean reconcileCalled = new AtomicBoolean(false);

        void reset() {
            reconcileThrows = false;
            depStatus = JobStatus.COMPLETED;
            reconcileCalled.set(false);
        }

        @Override
        public void reconcileProject(String group, String project, Lane lane, int lookaheadDays) {
            reconcileCalled.set(true);
            if (reconcileThrows) throw new RuntimeException("reconcile exploded");
        }

        @Override
        public void ensureServiceWorkflow(com.compute.model.ServiceDefinition def) {}

        @Override
        public void stopServiceWorkflow(String idPath, String group, String project, Lane lane) {}

        @Override
        public void scheduleJobWorkflow(JobDefinition def, LocalDate runDate) {}

        @Override
        public List<String> listRunningServiceIds(String group, String project, Lane lane) {
            return List.of();
        }

        @Override
        public List<String> listRunningJobWorkflowIds(String group, String project, Lane lane) {
            return List.of();
        }

        @Override
        public void cancelJobByWorkflowId(String workflowId, String group, String project, Lane lane) {}

        @Override
        public JobStatus queryJobStatus(String idPath, String group, String project,
                                        Lane lane, LocalDate runDate) {
            return depStatus;
        }
    }
}
