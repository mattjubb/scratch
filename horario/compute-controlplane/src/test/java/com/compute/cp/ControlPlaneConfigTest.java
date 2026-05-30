package com.compute.cp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneConfigTest {

    @Test
    void fromEnvReturnsDefaultsWhenNoEnvSet() {
        ControlPlaneConfig cfg = ControlPlaneConfig.fromEnv();

        // Defaults as coded in fromEnv()
        assertThat(cfg.httpPort()).isEqualTo(8080);
        // Use endsWith to be OS path-separator agnostic
        assertThat(cfg.definitionsDir().toString()).endsWith("definitions");
        assertThat(cfg.ocpNamespace()).isEqualTo("vasara");
        assertThat(cfg.dryRun()).isFalse();
    }

    @Test
    void constructedValueIsPreserved() {
        ControlPlaneConfig cfg = new ControlPlaneConfig(
                9090,
                java.nio.file.Paths.get("opt", "defs"),
                "http://images:8080",
                "my-namespace",
                true
        );
        assertThat(cfg.httpPort()).isEqualTo(9090);
        assertThat(cfg.definitionsDir().toString()).endsWith("defs");
        assertThat(cfg.imageVersionApiBaseUrl()).isEqualTo("http://images:8080");
        assertThat(cfg.ocpNamespace()).isEqualTo("my-namespace");
        assertThat(cfg.dryRun()).isTrue();
    }

    @Test
    void recordEquality() {
        ControlPlaneConfig a = new ControlPlaneConfig(8080,
                java.nio.file.Paths.get("deploy/definitions"), "", "vasara", false);
        ControlPlaneConfig b = new ControlPlaneConfig(8080,
                java.nio.file.Paths.get("deploy/definitions"), "", "vasara", false);
        assertThat(a).isEqualTo(b);
    }
}
