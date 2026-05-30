package com.compute.model;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceStateTest {

    private static ServiceDefinition def() {
        return new ServiceDefinition(
                ComputeId.of("/rates/swaps/pricer"), Lane.DEV, "rates", "swaps",
                "com.example.Pricer", "dev", 2,
                ResourceSpec.defaults(), List.of(), List.of(), List.of());
    }

    @Test
    void initialStateHasCorrectDefaults() {
        ServiceState s = ServiceState.initial(def());
        assertThat(s.status()).isEqualTo(ServiceStatus.STARTING);
        assertThat(s.desiredReplicas()).isEqualTo(2);
        assertThat(s.readyReplicas()).isEqualTo(0);
        assertThat(s.images()).isEmpty();
        assertThat(s.startTime()).isNotNull();
        assertThat(s.lastTransition()).isNotNull();
        assertThat(s.group()).isEqualTo("rates");
        assertThat(s.tag()).isEqualTo("dev");
    }

    @Test
    void withStatusUpdatesStatusAndMessage() {
        ServiceState s = ServiceState.initial(def()).withStatus(ServiceStatus.RUNNING, "up");
        assertThat(s.status()).isEqualTo(ServiceStatus.RUNNING);
        assertThat(s.message()).isEqualTo("up");
        assertThat(s.lastTransition()).isNotNull();
    }

    @Test
    void withStatusPreservesOtherFields() {
        ServiceState initial = ServiceState.initial(def());
        ServiceState s = initial.withStatus(ServiceStatus.DEGRADED, "partial");
        assertThat(s.id()).isEqualTo(initial.id());
        assertThat(s.desiredReplicas()).isEqualTo(initial.desiredReplicas());
        assertThat(s.startTime()).isEqualTo(initial.startTime());
    }

    @Test
    void withImagesUpdatesImageList() {
        List<ImageSpec> imgs = List.of(new ImageSpec("core", "registry/core:1", 0));
        ServiceState s = ServiceState.initial(def()).withImages(imgs);
        assertThat(s.images()).hasSize(1);
        assertThat(s.images().get(0).imageRef()).isEqualTo("registry/core:1");
    }

    @Test
    void withReplicasUpdatesCounts() {
        ServiceState s = ServiceState.initial(def()).withReplicas(3, 2);
        assertThat(s.desiredReplicas()).isEqualTo(3);
        assertThat(s.readyReplicas()).isEqualTo(2);
    }

    @Test
    void withStatusIsImmutable() {
        ServiceState original = ServiceState.initial(def());
        original.withStatus(ServiceStatus.STOPPED, "stopped");
        assertThat(original.status()).isEqualTo(ServiceStatus.STARTING);
    }
}
