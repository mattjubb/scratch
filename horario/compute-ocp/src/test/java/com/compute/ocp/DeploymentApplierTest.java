package com.compute.ocp;

import com.compute.model.ComputeId;
import com.compute.model.EnvVar;
import com.compute.model.ImageSpec;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.compute.model.PortSpec;
import com.compute.model.ResourceSpec;
import com.compute.model.ServiceDefinition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentApplierTest {

    private OcpClientHolder dryRun;
    private DeploymentApplier applier;

    @BeforeEach
    void setUp() {
        dryRun = new OcpClientHolder(true, "vasara");
        applier = new DeploymentApplier(dryRun, new PodSpecComposer());
    }

    private static ServiceDefinition svcDef() {
        return new ServiceDefinition(
                ComputeId.of("/rates/swaps/pricer"), Lane.DEV, "rates", "swaps",
                "com.example.Pricer", "dev", 2,
                new ResourceSpec("500m", "1Gi"),
                List.of(new EnvVar("MODE", "live")),
                List.of(new PortSpec("rest", 8080)),
                List.of());
    }

    private static List<ImageSpec> images() {
        return List.of(new ImageSpec("core", "registry/core:1", 0));
    }

    @Test
    void applyInDryRunReturnsBuildDeployment() {
        Deployment d = applier.apply(svcDef(), images());

        assertThat(d.getMetadata().getName()).startsWith("svc-");
        assertThat(d.getMetadata().getNamespace()).isEqualTo("vasara");
        assertThat(d.getSpec().getReplicas()).isEqualTo(2);
        assertThat(d.getMetadata().getLabels())
                .containsKey(ResourceNamer.LABEL_KIND)
                .containsKey(ResourceNamer.LABEL_ID);
    }

    @Test
    void applyNameIncludesLane() {
        Deployment d = applier.apply(svcDef(), images());
        assertThat(d.getMetadata().getName()).contains("dev");
    }

    @Test
    void scaleInDryRunDoesNotThrow() {
        LaneRef ref = new LaneRef("rates", "swaps", Lane.DEV);
        applier.scale(ComputeId.of("/rates/swaps/pricer"), ref, 0);
        // just verifying no exception
    }

    @Test
    void deleteInDryRunDoesNotThrow() {
        LaneRef ref = new LaneRef("rates", "swaps", Lane.DEV);
        applier.delete(ComputeId.of("/rates/swaps/pricer"), ref);
    }

    @Test
    void readyStatusInDryRunReturnsAllReady() {
        LaneRef ref = new LaneRef("rates", "swaps", Lane.DEV);
        DeploymentApplier.ReadyStatus s = applier.readyStatus(ComputeId.of("/rates/swaps/pricer"), ref);
        assertThat(s.allReady()).isTrue();
        assertThat(s.desired()).isEqualTo(1);
        assertThat(s.ready()).isEqualTo(1);
        assertThat(s.imagePullError()).isFalse();
    }

    @Test
    void applyWithMultipleImages() {
        List<ImageSpec> imgs = List.of(
                new ImageSpec("core", "registry/core:1", 0),
                new ImageSpec("ext",  "registry/ext:2",  1));
        Deployment d = applier.apply(svcDef(), imgs);
        assertThat(d.getSpec().getTemplate().getSpec().getInitContainers()).hasSize(2);
    }
}
