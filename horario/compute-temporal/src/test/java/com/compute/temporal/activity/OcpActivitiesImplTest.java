package com.compute.temporal.activity;

import com.compute.model.ComputeId;
import com.compute.model.EnvVar;
import com.compute.model.ImageSpec;
import com.compute.model.JobDefinition;
import com.compute.model.Lane;
import com.compute.model.PortSpec;
import com.compute.model.ResourceSpec;
import com.compute.model.ServiceDefinition;
import com.compute.model.SubtaskRequest;
import com.compute.model.TaskRequest;
import com.compute.ocp.DeploymentApplier;
import com.compute.ocp.JobApplier;
import com.compute.ocp.OcpClientHolder;
import com.compute.ocp.PodSpecComposer;
import com.compute.temporal.activity.impl.OcpActivitiesImpl;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OcpActivitiesImplTest {

    private OcpActivitiesImpl impl;

    @BeforeEach
    void setUp() {
        OcpClientHolder dryRun = new OcpClientHolder(true, "vasara");
        DeploymentApplier deployments = new DeploymentApplier(dryRun, new PodSpecComposer());
        JobApplier jobs = new JobApplier(dryRun, new PodSpecComposer());
        impl = new OcpActivitiesImpl(dryRun, deployments, jobs);
    }

    @Test
    void applyServiceReturnsDryRunResult() {
        ServiceDefinition def = svcDef("/rates/swaps/pricer");
        List<ImageSpec> images = List.of(new ImageSpec("core", "r/core:1", 0));

        OcpActivities.DeployResult r = impl.applyService(def, images);

        assertThat(r.deploymentName()).startsWith("svc-");
        assertThat(r.namespace()).isEqualTo("vasara");
    }

    @Test
    void serviceReadyStatusReturnsDryRunStatus() {
        OcpActivities.ReplicaStatus s = impl.serviceReadyStatus(
                ComputeId.of("/rates/swaps/pricer"), Lane.DEV, "rates", "swaps");
        assertThat(s.allReady()).isTrue();
        assertThat(s.desired()).isEqualTo(1);
        assertThat(s.ready()).isEqualTo(1);
    }

    @Test
    void scaleServiceDoesNotThrowInDryRun() {
        impl.scaleService(ComputeId.of("/rates/swaps/pricer"), Lane.DEV, "rates", "swaps", 0);
    }

    @Test
    void deleteServiceDoesNotThrowInDryRun() {
        impl.deleteService(ComputeId.of("/rates/swaps/pricer"), Lane.DEV, "rates", "swaps");
    }

    @Test
    void applyOcpJobReturnsDryRunResult() {
        JobDefinition def = jobDef("/rates/eod/curve-build");
        List<ImageSpec> images = List.of(new ImageSpec("core", "r/core:1", 0));
        LocalDate date = LocalDate.of(2026, 5, 25);

        OcpActivities.JobResult r = impl.applyOcpJob(def, images, date, Map.of("k", "v"));

        assertThat(r.jobName()).contains("curve-build");
        assertThat(r.namespace()).isEqualTo("vasara");
    }

    @Test
    void pollOcpJobReturnsDryRunOutcome() {
        // Dry-run JobApplier.poll returns RUNNING
        OcpActivities.JobPoll poll = impl.pollOcpJob("some-job");
        assertThat(poll).isEqualTo(OcpActivities.JobPoll.RUNNING);
    }

    @Test
    void deleteOcpJobDoesNotThrowInDryRun() {
        impl.deleteOcpJob("some-job");
    }

    @Test
    void applyTaskWorkersReturnsDryRunResult() {
        TaskRequest req = new TaskRequest(
                "task-xyz", "rates", "eod", Lane.DEV, "dev", 2,
                List.of(new SubtaskRequest("s1", "compute", Map.of())));
        List<ImageSpec> images = List.of(new ImageSpec("core", "r/core:1", 0));

        OcpActivities.JobResult r = impl.applyTaskWorkers(req, images, "127.0.0.1:7233", "rates-eod-dev");

        assertThat(r.jobName()).startsWith("task-");
        assertThat(r.namespace()).isEqualTo("vasara");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ServiceDefinition svcDef(String path) {
        return new ServiceDefinition(
                ComputeId.of(path), Lane.DEV, "rates", "swaps",
                "com.example.App", "dev", 1,
                ResourceSpec.defaults(),
                List.of(new EnvVar("MODE", "live")),
                List.of(new PortSpec("rest", 8080)),
                List.of());
    }

    private static JobDefinition jobDef(String path) {
        return new JobDefinition(
                ComputeId.of(path), Lane.DEV, "rates", "eod",
                "com.example.Job", "dev", "0 17 * * *", List.of(),
                ResourceSpec.defaults(), List.of(), Map.of(), false, 2);
    }
}
