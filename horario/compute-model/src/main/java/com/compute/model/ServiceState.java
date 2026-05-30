package com.compute.model;

import java.time.Instant;
import java.util.List;

public record ServiceState(
        ComputeId id,
        Lane lane,
        String group,
        String project,
        ServiceStatus status,
        List<ImageSpec> images,
        int desiredReplicas,
        int readyReplicas,
        Instant startTime,
        Instant lastTransition,
        String message,
        String host,
        String tag
) {
    public static ServiceState initial(ServiceDefinition def) {
        Instant now = Instant.now();
        return new ServiceState(
                def.id(), def.lane(), def.group(), def.project(),
                ServiceStatus.STARTING, List.of(), def.replicas(), 0,
                now, now, "", "", def.version()
        );
    }

    public ServiceState withStatus(ServiceStatus next, String message) {
        return new ServiceState(
                id, lane, group, project, next, images, desiredReplicas, readyReplicas,
                startTime, Instant.now(), message, host, tag
        );
    }

    public ServiceState withImages(List<ImageSpec> images) {
        return new ServiceState(
                id, lane, group, project, status, images, desiredReplicas, readyReplicas,
                startTime, lastTransition, message, host, tag
        );
    }

    public ServiceState withReplicas(int desired, int ready) {
        return new ServiceState(
                id, lane, group, project, status, images, desired, ready,
                startTime, lastTransition, message, host, tag
        );
    }
}
