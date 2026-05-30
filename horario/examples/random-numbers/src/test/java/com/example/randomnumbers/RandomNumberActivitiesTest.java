package com.example.randomnumbers;

import com.compute.model.SubtaskRequest;
import com.compute.model.SubtaskResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RandomNumberActivitiesTest {

    private final RandomNumberActivities act = new RandomNumberActivities();

    // ── basic contract ────────────────────────────────────────────────────────

    @Test
    void defaultsGenerateTenThousandNumbers() {
        SubtaskResult r = act.execute(req("sub-0001", Map.of()));
        assertThat(r.success()).isTrue();
        assertThat(r.subtaskId()).isEqualTo("sub-0001");
        assertThat((Number) r.output().get("count")).isEqualTo(10_000L);
    }

    @Test
    void returnsAllRequiredFields() {
        SubtaskResult r = act.execute(req("sub-0001", Map.of("count", 100)));
        assertThat(r.output()).containsKeys("subtaskId", "count", "sum", "min", "max", "mean", "stddev");
    }

    @Test
    void minIsNotGreaterThanMax() {
        SubtaskResult r = act.execute(req("sub-0", Map.of("count", 1_000, "seed", 42)));
        double min = ((Number) r.output().get("min")).doubleValue();
        double max = ((Number) r.output().get("max")).doubleValue();
        assertThat(min).isLessThanOrEqualTo(max);
    }

    @Test
    void outputsInDefaultRange() {
        SubtaskResult r = act.execute(req("sub-0", Map.of("count", 5_000, "seed", 7)));
        double min = ((Number) r.output().get("min")).doubleValue();
        double max = ((Number) r.output().get("max")).doubleValue();
        assertThat(min).isGreaterThanOrEqualTo(0.0);
        assertThat(max).isLessThan(1.0);
    }

    // ── statistics ────────────────────────────────────────────────────────────

    @Test
    void meanIsNearPointFiveForUniformDistribution() {
        // Large seeded sample → mean converges to 0.5 by LLN
        SubtaskResult r = act.execute(req("s", Map.of("count", 100_000, "seed", 99)));
        double mean = ((Number) r.output().get("mean")).doubleValue();
        assertThat(mean).isCloseTo(0.5, within(0.01));
    }

    @Test
    void stddevIsNearExpectedForUniform() {
        // Uniform[0,1] stddev = 1/√12 ≈ 0.2887
        SubtaskResult r = act.execute(req("s", Map.of("count", 100_000, "seed", 55)));
        double stddev = ((Number) r.output().get("stddev")).doubleValue();
        assertThat(stddev).isCloseTo(1.0 / Math.sqrt(12.0), within(0.005));
    }

    @Test
    void sumEqualsCountTimesMeanApproximately() {
        SubtaskResult r = act.execute(req("s", Map.of("count", 1_000, "seed", 11)));
        long count   = ((Number) r.output().get("count")).longValue();
        double mean  = ((Number) r.output().get("mean")).doubleValue();
        double sum   = ((Number) r.output().get("sum")).doubleValue();
        assertThat(sum).isCloseTo(count * mean, within(1e-6));
    }

    // ── custom range ──────────────────────────────────────────────────────────

    @Test
    void customRangeScalesOutput() {
        // All samples must be in [10, 20)
        SubtaskResult r = act.execute(req("s", Map.of("count", 5_000, "seed", 3, "min", 10.0, "max", 20.0)));
        double min = ((Number) r.output().get("min")).doubleValue();
        double max = ((Number) r.output().get("max")).doubleValue();
        assertThat(min).isGreaterThanOrEqualTo(10.0);
        assertThat(max).isLessThan(20.0);
    }

    @Test
    void invalidRangeThrows() {
        assertThatThrownBy(() ->
            act.execute(req("s", Map.of("count", 10, "min", 5.0, "max", 5.0)))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    // ── reproducibility ───────────────────────────────────────────────────────

    @Test
    void seededRunsAreReproducible() {
        Map<String, Object> args = Map.of("count", 500, "seed", 42);
        SubtaskResult r1 = act.execute(req("sub-007", args));
        SubtaskResult r2 = act.execute(req("sub-007", args));
        assertThat(r1.output().get("sum")).isEqualTo(r2.output().get("sum"));
    }

    @Test
    void differentSubtaskIdsProduceDifferentResults() {
        Map<String, Object> args = Map.of("count", 1_000, "seed", 1);
        SubtaskResult r1 = act.execute(req("sub-001", args));
        SubtaskResult r2 = act.execute(req("sub-002", args));
        // Different per-subtask seed offset → different sums (extremely unlikely to collide)
        assertThat(r1.output().get("sum")).isNotEqualTo(r2.output().get("sum"));
    }

    // ── SubmitTask body builder ───────────────────────────────────────────────

    @Test
    void submitTaskBodyContainsCorrectSubtaskCount() {
        String body = SubmitTask.buildRequestBody("dev", 1000, 10_000, 50);
        // Count occurrences of "subtaskId" — one per subtask entry.
        int count = 0, idx = 0;
        while ((idx = body.indexOf("subtaskId", idx)) != -1) { count++; idx++; }
        assertThat(count).isEqualTo(1000);
    }

    @Test
    void submitTaskBodyContainsCorrectKind() {
        String body = SubmitTask.buildRequestBody("dev", 5, 100, 2);
        assertThat(body).contains("\"kind\":\"random-numbers\"");
    }

    @Test
    void submitTaskBodyNamesSubtasksSequentially() {
        String body = SubmitTask.buildRequestBody("dev", 3, 100, 1);
        assertThat(body).contains("sub-0000").contains("sub-0001").contains("sub-0002");
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static SubtaskRequest req(String id, Map<String, Object> args) {
        return new SubtaskRequest(id, "random-numbers", args);
    }
}
