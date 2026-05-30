package com.compute.model;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComputeIdTest {

    @Test
    void derivesIdFromRelativePath() {
        ComputeId id = ComputeId.fromRelativePath(Path.of("trade", "booking", "booker.yaml"));
        assertThat(id.path()).isEqualTo("/trade/booking/booker");
        assertThat(id.name()).isEqualTo("booker");
        assertThat(id.group()).isEqualTo("trade");
        assertThat(id.project()).isEqualTo("booking");
    }

    @Test
    void derivesIdFromBackslashPath() {
        ComputeId id = ComputeId.fromRelativePath(Path.of("rates", "eod", "curve-build.yaml"));
        assertThat(id.path()).isEqualTo("/rates/eod/curve-build");
    }

    @Test
    void groupProjectForShallowId() {
        ComputeId id = ComputeId.of("/single");
        assertThat(id.group()).isEqualTo("single");
        assertThat(id.project()).isEmpty();
    }

    @Test
    void rejectsInvalidPaths() {
        assertThatThrownBy(() -> new ComputeId("noleadingslash")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComputeId("/trailing/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComputeId("/double//slash")).isInstanceOf(IllegalArgumentException.class);
    }
}
