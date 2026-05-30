package com.compute.model;

/** Compute resources requested by a workload pod. */
public record ResourceSpec(String cpu, String memory) {
    public static ResourceSpec defaults() {
        return new ResourceSpec("500m", "1Gi");
    }
}
