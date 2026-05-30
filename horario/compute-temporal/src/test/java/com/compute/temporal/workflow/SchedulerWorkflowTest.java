package com.compute.temporal.workflow;

import com.compute.model.ComputeId;
import com.compute.model.JobDefinition;
import com.compute.model.JobStatus;
import com.compute.model.Lane;
import com.compute.model.ResourceSpec;
import com.compute.model.ServiceDefinition;
import com.compute.temporal.activity.DefinitionActivities;
import com.compute.temporal.activity.OrchestrationActivities;
import com.compute.temporal.workflow.impl.SchedulerWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class SchedulerWorkflowTest {

    private static final String QUEUE = "scheduler-test-queue";

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;

    private final StubDefinitionActivities defActs  = new StubDefinitionActivities();
    private final StubOrchActivities       orchActs = new StubOrchActivities();

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(SchedulerWorkflowImpl.class);
        worker.registerActivitiesImplementations(defActs, orchActs);
        testEnv.start();
        client = testEnv.getWorkflowClient();
        defActs.reset();
        orchActs.reset();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private SchedulerWorkflow stub(String id) {
        return client.newWorkflowStub(SchedulerWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(QUEUE)
                        .setWorkflowId(id)
                        .build());
    }

    @Test
    void reconcileWithNoDefinitionsReturnsEmptyReport() {
        SchedulerWorkflow wf = stub("sched-empty");
        SchedulerReport r = wf.reconcile();
        assertThat(r.servicesStarted()).isEmpty();
        assertThat(r.servicesStopped()).isEmpty();
        assertThat(r.jobsScheduled()).isEmpty();
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void reconcileStartsNewService() {
        defActs.services = List.of(svcDef("/rates/swaps/pricer", "rates", "swaps"));

        SchedulerWorkflow wf = stub("sched-new-svc");
        SchedulerReport r = wf.reconcile();

        assertThat(r.servicesStarted()).containsExactly("/rates/swaps/pricer");
        assertThat(orchActs.ensureServiceCalls.get()).isEqualTo(1);
    }

    @Test
    void reconcileSchedulesJobsForToday() {
        defActs.jobs = List.of(
                jobDef("/rates/eod/curve-build", "rates", "eod"),
                jobDef("/rates/eod/market-data", "rates", "eod"));

        SchedulerWorkflow wf = stub("sched-jobs");
        SchedulerReport r = wf.reconcile();

        assertThat(r.jobsScheduled()).hasSize(2);
        assertThat(orchActs.scheduleJobCalls.get()).isEqualTo(2);
    }

    @Test
    void reconcileStopsRemovedService() {
        // YAML has "new-svc" in rates/swaps but "old-svc" is also running in that triple
        defActs.services = List.of(svcDef("/rates/swaps/new-svc", "rates", "swaps"));
        orchActs.runningServiceIds = List.of("/rates/swaps/new-svc", "/rates/swaps/old-svc");

        SchedulerWorkflow wf = stub("sched-stop-svc");
        SchedulerReport r = wf.reconcile();

        // new-svc is redeployed (already running), old-svc is stopped
        assertThat(r.servicesRedeployed()).containsExactly("/rates/swaps/new-svc");
        assertThat(r.servicesStopped()).containsExactly("/rates/swaps/old-svc");
        assertThat(orchActs.stopServiceCalls.get()).isEqualTo(1);
    }

    @Test
    void reconcileMarksBothStartedAndRedeploy() {
        // Two services in YAML; pricer is already running, analytics is new
        orchActs.runningServiceIds = List.of("/rates/swaps/pricer");
        defActs.services = List.of(
                svcDef("/rates/swaps/pricer", "rates", "swaps"),
                svcDef("/rates/swaps/analytics", "rates", "swaps"));

        SchedulerWorkflow wf = stub("sched-redeploy");
        SchedulerReport r = wf.reconcile();

        assertThat(r.servicesRedeployed()).containsExactly("/rates/swaps/pricer");
        assertThat(r.servicesStarted()).containsExactly("/rates/swaps/analytics");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ServiceDefinition svcDef(String path, String group, String project) {
        return new ServiceDefinition(
                ComputeId.of(path), Lane.DEV, group, project,
                "com.example.App", "dev", 1,
                ResourceSpec.defaults(), List.of(), List.of(), List.of());
    }

    private static JobDefinition jobDef(String path, String group, String project) {
        return new JobDefinition(
                ComputeId.of(path), Lane.DEV, group, project,
                "com.example.Job", "dev", "0 17 * * *", List.of(),
                ResourceSpec.defaults(), List.of(), Map.of(), false, 2);
    }

    // ── activity stubs ─────────────────────────────────────────────────────────

    static class StubDefinitionActivities implements DefinitionActivities {
        List<ServiceDefinition> services = List.of();
        List<JobDefinition> jobs = List.of();

        void reset() { services = List.of(); jobs = List.of(); }

        @Override
        public Loaded loadAll() { return new Loaded(services, jobs); }

        @Override
        public Loaded loadForProject(String group, String project) {
            return new Loaded(
                    services.stream().filter(s -> s.group().equals(group) && s.project().equals(project)).toList(),
                    jobs.stream().filter(j -> j.group().equals(group) && j.project().equals(project)).toList());
        }
    }

    static class StubOrchActivities implements OrchestrationActivities {
        List<String> runningServiceIds = List.of();
        final AtomicInteger ensureServiceCalls = new AtomicInteger();
        final AtomicInteger stopServiceCalls   = new AtomicInteger();
        final AtomicInteger scheduleJobCalls   = new AtomicInteger();

        void reset() {
            runningServiceIds = List.of();
            ensureServiceCalls.set(0);
            stopServiceCalls.set(0);
            scheduleJobCalls.set(0);
        }

        @Override
        public void ensureServiceWorkflow(ServiceDefinition def) {
            ensureServiceCalls.incrementAndGet();
        }

        @Override
        public void stopServiceWorkflow(String idPath, String group, String project, Lane lane) {
            stopServiceCalls.incrementAndGet();
        }

        @Override
        public void scheduleJobWorkflow(JobDefinition def, LocalDate runDate) {
            scheduleJobCalls.incrementAndGet();
        }

        @Override
        public List<String> listRunningServiceIds(String group, String project, Lane lane) {
            return runningServiceIds;
        }

        @Override
        public void reconcileProject(String group, String project, Lane lane, int lookaheadDays) {}

        @Override
        public List<String> listRunningJobWorkflowIds(String group, String project, Lane lane) {
            return List.of();
        }

        @Override
        public void cancelJobByWorkflowId(String workflowId, String group, String project, Lane lane) {}

        @Override
        public JobStatus queryJobStatus(String idPath, String group, String project,
                                        Lane lane, LocalDate runDate) {
            return JobStatus.COMPLETED;
        }
    }
}
