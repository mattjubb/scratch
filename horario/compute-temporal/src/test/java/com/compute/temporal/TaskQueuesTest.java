package com.compute.temporal;

import com.compute.model.Lane;
import com.compute.model.LaneRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskQueuesTest {

    @Test
    void forNamespaceFromRef() {
        LaneRef ref = new LaneRef("rates", "swaps", Lane.DEV);
        assertThat(TaskQueues.forNamespace(ref)).isEqualTo("rates-swaps-dev");
    }

    @Test
    void forNamespaceFromString() {
        assertThat(TaskQueues.forNamespace("rates-swaps-dev")).isEqualTo("rates-swaps-dev");
    }

    @Test
    void forSystemIsConstant() {
        assertThat(TaskQueues.forSystem()).isEqualTo("vasara-system");
        assertThat(TaskQueues.forSystem()).isEqualTo(TaskQueues.forSystem());
    }

    @Test
    void forTaskPrefixesTaskId() {
        assertThat(TaskQueues.forTask("abc-123")).isEqualTo("task-abc-123");
    }

    @Test
    void forNamespaceAndForNamespaceFromRefAreConsistent() {
        LaneRef ref = new LaneRef("fx", "options", Lane.PROD);
        assertThat(TaskQueues.forNamespace(ref))
                .isEqualTo(TaskQueues.forNamespace(ref.temporalNamespace()));
    }
}
