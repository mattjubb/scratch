package com.compute.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LaneRefTest {

    @Test
    void buildsTemporalNamespace() {
        assertThat(new LaneRef("rates", "swaps", Lane.DEV).temporalNamespace())
                .isEqualTo("rates-swaps-dev");
        assertThat(new LaneRef("fx", "options", Lane.PROD).temporalNamespace())
                .isEqualTo("fx-options-prod");
    }
}
