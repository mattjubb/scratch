package com.compute.temporal;

import com.compute.model.ComputeId;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowIdsTest {

    @Test
    void serviceId() {
        assertThat(WorkflowIds.service(ComputeId.of("/rates/swaps/pricer")))
                .isEqualTo("service:/rates/swaps/pricer");
    }

    @Test
    void jobIdIncludesDate() {
        String id = WorkflowIds.job(ComputeId.of("/rates/eod/curve-build"),
                LocalDate.of(2026, 5, 25));
        assertThat(id).isEqualTo("job:/rates/eod/curve-build:2026-05-25");
    }

    @Test
    void taskId() {
        assertThat(WorkflowIds.task("abc-123")).isEqualTo("task:abc-123");
    }

    @Test
    void subtaskId() {
        assertThat(WorkflowIds.subtask("parent-1", "child-1"))
                .isEqualTo("task:parent-1:child-1");
    }

    @Test
    void schedulerGlobal() {
        assertThat(WorkflowIds.scheduler()).isEqualTo("scheduler:global");
    }

    @Test
    void schedulerJobHasNoDateSuffix() {
        String id = WorkflowIds.schedulerJob(ComputeId.of("/rates/eod/scheduler"));
        assertThat(id).isEqualTo("job:/rates/eod/scheduler");
        assertThat(id).doesNotContain("2026"); // no date
    }

    @Test
    void scheduleIdPrefix() {
        assertThat(WorkflowIds.scheduleId(ComputeId.of("/rates/eod/scheduler")))
                .isEqualTo("sched:/rates/eod/scheduler");
    }

    @Test
    void jobIdAndSchedulerJobIdSharePrefix() {
        ComputeId id = ComputeId.of("/rates/eod/curve-build");
        // Both start with "job:" — the Schedule's SKIP overlap policy distinguishes them
        assertThat(WorkflowIds.job(id, LocalDate.now())).startsWith("job:");
        assertThat(WorkflowIds.schedulerJob(id)).startsWith("job:");
    }
}
