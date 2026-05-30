package com.compute.model;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRequestTest {

    private static TaskRequest sample() {
        return new TaskRequest(
                "task-abc-123", "rates", "swaps", Lane.DEV, "dev", 4,
                List.of(new SubtaskRequest("sub-1", "price", java.util.Map.of("k", "v"))));
    }

    @Test
    void laneRefBuildsFromGroupProjectLane() {
        LaneRef ref = sample().laneRef();
        assertThat(ref.group()).isEqualTo("rates");
        assertThat(ref.project()).isEqualTo("swaps");
        assertThat(ref.lane()).isEqualTo(Lane.DEV);
    }

    @Test
    void taskQueueUsesTaskId() {
        assertThat(sample().taskQueue()).isEqualTo("task-task-abc-123");
    }

    @Test
    void newTaskIdIsValidUuid() {
        String id = TaskRequest.newTaskId();
        assertThat(id).isNotBlank();
        // should not throw
        UUID.fromString(id);
    }

    @Test
    void newTaskIdIsUnique() {
        assertThat(TaskRequest.newTaskId()).isNotEqualTo(TaskRequest.newTaskId());
    }

    @Test
    void subtaskResultFactories() {
        SubtaskResult ok = SubtaskResult.ok("sub-1", java.util.Map.of("count", 5));
        assertThat(ok.success()).isTrue();
        assertThat(ok.subtaskId()).isEqualTo("sub-1");
        assertThat(ok.output()).containsEntry("count", 5);

        SubtaskResult failed = SubtaskResult.failed("sub-2", "boom");
        assertThat(failed.success()).isFalse();
        assertThat(failed.message()).isEqualTo("boom");
        assertThat(failed.output()).isEmpty();
    }
}
