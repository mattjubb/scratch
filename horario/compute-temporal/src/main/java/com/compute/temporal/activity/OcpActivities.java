package com.compute.temporal.activity;

import com.compute.model.ComputeId;
import com.compute.model.ImageSpec;
import com.compute.model.JobDefinition;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.compute.model.ServiceDefinition;
import com.compute.model.TaskRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Fabric8-backed OCP operations. */
@ActivityInterface
public interface OcpActivities {

    @ActivityMethod
    DeployResult applyService(ServiceDefinition def, List<ImageSpec> images);

    @ActivityMethod
    ReplicaStatus serviceReadyStatus(ComputeId id, Lane lane, String group, String project);

    @ActivityMethod
    void scaleService(ComputeId id, Lane lane, String group, String project, int replicas);

    @ActivityMethod
    void deleteService(ComputeId id, Lane lane, String group, String project);

    @ActivityMethod
    JobResult applyOcpJob(JobDefinition def, List<ImageSpec> images, LocalDate runDate, Map<String, String> args);

    @ActivityMethod
    JobPoll pollOcpJob(String jobName);

    @ActivityMethod
    void deleteOcpJob(String jobName);

    @ActivityMethod
    JobResult applyTaskWorkers(TaskRequest task, List<ImageSpec> images, String temporalTarget, String temporalNamespace);

    record DeployResult(String deploymentName, String namespace) {}

    record JobResult(String jobName, String namespace) {}

    record ReplicaStatus(int desired, int ready, boolean allReady, boolean imagePullError) {}

    enum JobPoll {
        /** OCP Job not yet active (pod not scheduled). */
        PENDING,
        /** Pod active; only init containers running — main container not yet started. */
        STARTING,
        /** Main ({@code app}) container is running. */
        RUNNING,
        SUCCEEDED, FAILED
    }

    /** Convenience converter on the calling side. */
    static LaneRef ref(Lane lane, String group, String project) {
        return new LaneRef(group, project, lane);
    }
}
