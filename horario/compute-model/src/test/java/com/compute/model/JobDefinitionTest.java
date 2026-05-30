package com.compute.model;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobDefinitionTest {

    private static JobDefinition regular() {
        return new JobDefinition(
                ComputeId.of("/rates/eod/curve-build"), Lane.DEV, "rates", "eod",
                "com.example.CurveBuildJob", "dev", "0 17 * * *",
                List.of(ComputeId.of("/rates/eod/market-data")),
                ResourceSpec.defaults(), List.of(),
                Map.of("curve_set", "all"), false, 2);
    }

    private static JobDefinition scheduler() {
        return new JobDefinition(
                ComputeId.of("/rates/eod/scheduler"), Lane.DEV, "rates", "eod",
                "", "dev", "0 0 * * *",
                List.of(), ResourceSpec.defaults(), List.of(),
                Map.of(), true, 3);
    }

    @Test
    void laneRefBuildsCorrectly() {
        LaneRef ref = regular().laneRef();
        assertThat(ref.group()).isEqualTo("rates");
        assertThat(ref.project()).isEqualTo("eod");
        assertThat(ref.lane()).isEqualTo(Lane.DEV);
        assertThat(ref.temporalNamespace()).isEqualTo("rates-eod-dev");
    }

    @Test
    void regularJobIsNotScheduler() {
        assertThat(regular().scheduler()).isFalse();
    }

    @Test
    void schedulerJobHasFlag() {
        assertThat(scheduler().scheduler()).isTrue();
        assertThat(scheduler().lookaheadDays()).isEqualTo(3);
    }

    @Test
    void depsListIsAccessible() {
        assertThat(regular().deps()).hasSize(1);
        assertThat(regular().deps().get(0).path()).isEqualTo("/rates/eod/market-data");
    }

    @Test
    void defaultArgsAreAccessible() {
        assertThat(regular().defaultArgs()).containsEntry("curve_set", "all");
    }
}
