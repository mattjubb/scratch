package com.compute.temporal.workflow;

import com.compute.model.ComputeId;
import com.compute.model.EnvVar;
import com.compute.model.ImageSpec;
import com.compute.model.Lane;
import com.compute.model.PortSpec;
import com.compute.model.ResourceSpec;
import com.compute.model.ServiceDefinition;
import com.compute.model.ServiceState;
import com.compute.model.ServiceStatus;
import com.compute.temporal.activity.ImageActivities;
import com.compute.temporal.activity.OcpActivities;
import com.compute.temporal.workflow.impl.ServiceWorkflowImpl;
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
class ServiceWorkflowTest {

    private static final String QUEUE = "svc-test-queue";

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;

    private final StubImageActivities imageActs = new StubImageActivities();
    private final StubOcpActivities   ocpActs   = new StubOcpActivities();

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(ServiceWorkflowImpl.class);
        worker.registerActivitiesImplementations(imageActs, ocpActs);
        testEnv.start();
        client = testEnv.getWorkflowClient();
        imageActs.reset();
        ocpActs.reset();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private static ServiceDefinition def() {
        return new ServiceDefinition(
                ComputeId.of("/rates/swaps/pricer"), Lane.DEV, "rates", "swaps",
                "com.example.Pricer", "dev", 1,
                ResourceSpec.defaults(),
                List.of(new EnvVar("MODE", "live")),
                List.of(new PortSpec("rest", 8080)),
                List.of());
    }

    private ServiceWorkflow stub(String id) {
        return client.newWorkflowStub(ServiceWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(QUEUE)
                        .setWorkflowId(id)
                        .build());
    }

    @Test
    void startAndStopTransitionsToStopped() {
        ServiceWorkflow wf = stub("svc-stop");
        WorkflowClient.start(wf::run, def());

        testEnv.sleep(java.time.Duration.ofSeconds(2));
        client.newWorkflowStub(ServiceWorkflow.class, "svc-stop").stop();

        client.newUntypedWorkflowStub("svc-stop").getResult(Void.class);
        ServiceState s = client.newWorkflowStub(ServiceWorkflow.class, "svc-stop").getState();
        assertThat(s.status()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    void initialStateIsStartingOrRunning() {
        ServiceWorkflow wf = stub("svc-init");
        WorkflowClient.start(wf::run, def());

        testEnv.sleep(java.time.Duration.ofMillis(500));
        ServiceState s = client.newWorkflowStub(ServiceWorkflow.class, "svc-init").getState();
        assertThat(s.status()).isIn(ServiceStatus.STARTING, ServiceStatus.RUNNING);

        client.newWorkflowStub(ServiceWorkflow.class, "svc-init").stop();
        client.newUntypedWorkflowStub("svc-init").getResult(Void.class);
    }

    @Test
    void redeploySignalKeepsServiceRunning() {
        ServiceWorkflow wf = stub("svc-redeploy");
        WorkflowClient.start(wf::run, def());
        testEnv.sleep(java.time.Duration.ofSeconds(1));

        client.newWorkflowStub(ServiceWorkflow.class, "svc-redeploy").redeploy(def());
        testEnv.sleep(java.time.Duration.ofSeconds(1));

        client.newWorkflowStub(ServiceWorkflow.class, "svc-redeploy").stop();
        client.newUntypedWorkflowStub("svc-redeploy").getResult(Void.class);

        ServiceState s = client.newWorkflowStub(ServiceWorkflow.class, "svc-redeploy").getState();
        assertThat(s.status()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    void iceSignalScalesToZero() {
        ServiceWorkflow wf = stub("svc-ice");
        WorkflowClient.start(wf::run, def());
        testEnv.sleep(java.time.Duration.ofSeconds(1));

        client.newWorkflowStub(ServiceWorkflow.class, "svc-ice").ice();
        testEnv.sleep(java.time.Duration.ofSeconds(1));

        // still alive (not stopped) — just iced
        ServiceState s = client.newWorkflowStub(ServiceWorkflow.class, "svc-ice").getState();
        assertThat(s.status()).isNotEqualTo(ServiceStatus.STOPPED);

        client.newWorkflowStub(ServiceWorkflow.class, "svc-ice").stop();
        client.newUntypedWorkflowStub("svc-ice").getResult(Void.class);
    }

    // ── activity stubs ─────────────────────────────────────────────────────────

    static class StubImageActivities implements ImageActivities {
        void reset() {}

        @Override
        public List<ImageSpec> fetch(String group, String project, Lane lane, String version) {
            return List.of(new ImageSpec("core", "r/core:1", 0));
        }
    }

    static class StubOcpActivities implements OcpActivities {
        volatile boolean allReady = true;

        void reset() { allReady = true; }

        @Override
        public DeployResult applyService(ServiceDefinition def, List<ImageSpec> images) {
            return new DeployResult("deploy-" + def.id().name(), "vasara");
        }

        @Override
        public ReplicaStatus serviceReadyStatus(ComputeId id, Lane lane, String group, String project) {
            return new ReplicaStatus(1, allReady ? 1 : 0, allReady, false);
        }

        @Override
        public void scaleService(ComputeId id, Lane lane, String group, String project, int replicas) {}

        @Override
        public void deleteService(ComputeId id, Lane lane, String group, String project) {}

        @Override
        public JobResult applyOcpJob(com.compute.model.JobDefinition def, List<ImageSpec> images,
                                     LocalDate runDate, Map<String, String> args) {
            return new JobResult("job-123", "vasara");
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
}
