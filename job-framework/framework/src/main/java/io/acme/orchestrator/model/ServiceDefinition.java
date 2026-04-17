package io.acme.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A service runs indefinitely as an OCP Deployment.
 * <p>
 * Like a job it pulls its code from a stack of {@link CodebaseLayer}s via init
 * containers, but the main container stays up. An optional HTTP port is
 * exposed via a Service; readiness/liveness probes target that port.
 */
public record ServiceDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("replicas") int replicas,
        @JsonProperty("runtime") RuntimeSpec runtime,
        @JsonProperty("httpPort") Integer httpPort,
        @JsonProperty("readinessPath") String readinessPath,
        @JsonProperty("livenessPath") String livenessPath
) {
    @JsonCreator
    public ServiceDefinition {
        if (name == null || !name.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException(
                    "name must be a valid DNS-1123 label: " + name);
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace required");
        }
        if (replicas < 1) replicas = 1;
        if (runtime == null) throw new IllegalArgumentException("runtime required");
        if (httpPort != null) {
            if (readinessPath == null) readinessPath = "/health/ready";
            if (livenessPath == null) livenessPath = "/health/live";
        }
    }
}
