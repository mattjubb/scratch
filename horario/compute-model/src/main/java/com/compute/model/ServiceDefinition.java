package com.compute.model;

import java.util.List;

/**
 * A long-running service.
 *
 * @param id           derived from YAML path
 * @param lane         target lane
 * @param group        group label (also part of Temporal namespace)
 * @param project      project label
 * @param mainClass    fully qualified main class to launch in the main container
 * @param version      ImageVersionAPI version key (e.g. {@code dev}, {@code 1.42})
 * @param replicas     desired replica count when RUNNING
 * @param resources    cpu/memory request
 * @param env          extra env vars to inject
 * @param ports        container ports (REST/gRPC/Solace if applicable)
 * @param args         static command-line args to append
 */
public record ServiceDefinition(
        ComputeId id,
        Lane lane,
        String group,
        String project,
        String mainClass,
        String version,
        int replicas,
        ResourceSpec resources,
        List<EnvVar> env,
        List<PortSpec> ports,
        List<String> args
) {
    public LaneRef laneRef() {
        return new LaneRef(group, project, lane);
    }
}
