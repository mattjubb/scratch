package com.compute.ocp;

import com.compute.model.ComputeId;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceNamerTest {

    @Test
    void sanitizesId() {
        assertThat(ResourceNamer.sanitize(ComputeId.of("/Rates/Swaps/Pricer_v2")))
                .isEqualTo("rates-swaps-pricer-v2");
    }

    @Test
    void deploymentNameFitsK8sLimit() {
        String name = ResourceNamer.deploymentName(
                ComputeId.of("/group/project/some-very-long-name-that-might-exceed-the-limit"),
                new LaneRef("group", "project", Lane.PROD));
        assertThat(name.length()).isLessThanOrEqualTo(63);
        assertThat(name).startsWith("svc-");
    }

    @Test
    void jobNameIncludesDate() {
        String n = ResourceNamer.jobName(ComputeId.of("/rates/eod/curve-build"), "20260525");
        assertThat(n).isEqualTo("job-rates-eod-curve-build-20260525");
    }

    @Test
    void sanitizeStringCollapsesDashes() {
        assertThat(ResourceNamer.sanitize("a//b///c")).isEqualTo("a-b-c");
    }

    @Test
    void sanitizeStringTruncatesAt53() {
        String long53 = "a".repeat(60);
        assertThat(ResourceNamer.sanitize(long53)).hasSize(53);
    }

    @Test
    void taskJobNameRemovesDashes() {
        String n = ResourceNamer.taskJobName("abc-def-123");
        assertThat(n).isEqualTo("task-abcdef123");
    }

    @Test
    void baseLabelsContainsGroupProjectLane() {
        LaneRef ref = new LaneRef("rates", "swaps", Lane.PROD);
        java.util.Map<String, String> m = ResourceNamer.baseLabels(ref);
        assertThat(m.get(ResourceNamer.LABEL_GROUP)).isEqualTo("rates");
        assertThat(m.get(ResourceNamer.LABEL_PROJECT)).isEqualTo("swaps");
        assertThat(m.get(ResourceNamer.LABEL_LANE)).isEqualTo("prod");
    }

    @Test
    void labelConstantsHaveVasaraPrefix() {
        assertThat(ResourceNamer.LABEL_KIND).startsWith(ResourceNamer.LABEL_PREFIX);
        assertThat(ResourceNamer.LABEL_ID).startsWith(ResourceNamer.LABEL_PREFIX);
        assertThat(ResourceNamer.LABEL_RUN_DATE).startsWith(ResourceNamer.LABEL_PREFIX);
    }
}
