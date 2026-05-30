package com.compute.ocp;

import com.compute.model.ComputeId;
import com.compute.model.ImageSpec;
import com.compute.model.JobDefinition;
import com.compute.model.Lane;
import com.compute.model.ResourceSpec;
import com.compute.model.SubtaskRequest;
import com.compute.model.TaskRequest;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobApplierTest {

    private OcpClientHolder dryRun;
    private JobApplier applier;

    @BeforeEach
    void setUp() {
        dryRun = new OcpClientHolder(true, "vasara");
        applier = new JobApplier(dryRun, new PodSpecComposer());
    }

    private static JobDefinition jobDef() {
        return new JobDefinition(
                ComputeId.of("/rates/eod/curve-build"), Lane.DEV, "rates", "eod",
                "com.example.CurveBuild", "dev", "0 17 * * *", List.of(),
                ResourceSpec.defaults(), List.of(), Map.of(), false, 2);
    }

    private static List<ImageSpec> images() {
        return List.of(new ImageSpec("core", "registry/core:1", 0));
    }

    @Test
    void applyForJobInDryRunReturnsJob() {
        LocalDate date = LocalDate.of(2026, 5, 25);
        Job j = applier.applyForJob(jobDef(), images(), date, Map.of("key", "val"));

        assertThat(j.getMetadata().getName()).contains("curve-build");
        assertThat(j.getMetadata().getName()).contains("20260525");
        assertThat(j.getMetadata().getNamespace()).isEqualTo("vasara");
        assertThat(j.getSpec().getCompletions()).isEqualTo(1);
        assertThat(j.getSpec().getBackoffLimit()).isEqualTo(0);
    }

    @Test
    void applyForJobIncludes_keyValueArgs() {
        LocalDate date = LocalDate.of(2026, 5, 25);
        Job j = applier.applyForJob(jobDef(), images(), date, Map.of("curve_set", "all"));
        String cmd = j.getSpec().getTemplate().getSpec().getContainers().get(0).getCommand().get(2);
        assertThat(cmd).contains("--curve_set=all");
    }

    @Test
    void applyForTaskWorkersInDryRunReturnsJob() {
        TaskRequest req = new TaskRequest(
                "task-abc-123", "rates", "eod", Lane.DEV, "dev", 3,
                List.of(
                        new SubtaskRequest("s1", "compute", Map.of()),
                        new SubtaskRequest("s2", "compute", Map.of()),
                        new SubtaskRequest("s3", "compute", Map.of())));
        Job j = applier.applyForTaskWorkers(req, images(), "127.0.0.1:7233", "rates-eod-dev");

        assertThat(j.getMetadata().getName()).startsWith("task-");
        assertThat(j.getSpec().getParallelism()).isEqualTo(3);
        assertThat(j.getSpec().getCompletions()).isEqualTo(3);
    }

    @Test
    void pollInDryRunReturnsRunning() {
        assertThat(applier.poll("some-job")).isEqualTo(JobApplier.Outcome.RUNNING);
    }

    @Test
    void deleteInDryRunDoesNotThrow() {
        applier.delete("some-job"); // no exception expected
    }

    @Test
    void jobLabelsContainKindAndId() {
        LocalDate date = LocalDate.of(2026, 5, 25);
        Job j = applier.applyForJob(jobDef(), images(), date, Map.of());
        Map<String, String> labels = j.getMetadata().getLabels();
        assertThat(labels).containsKey(ResourceNamer.LABEL_KIND);
        assertThat(labels.get(ResourceNamer.LABEL_KIND)).isEqualTo("job");
        assertThat(labels).containsKey(ResourceNamer.LABEL_RUN_DATE);
    }
}
