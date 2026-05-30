package com.compute.temporal.activity.impl;

import com.compute.model.ComputeId;
import com.compute.model.ImageSpec;
import com.compute.model.JobDefinition;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.compute.model.ServiceDefinition;
import com.compute.model.TaskRequest;
import com.compute.ocp.DeploymentApplier;
import com.compute.ocp.JobApplier;
import com.compute.ocp.OcpClientHolder;
import com.compute.ocp.ResourceNamer;
import com.compute.temporal.activity.OcpActivities;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class OcpActivitiesImpl implements OcpActivities {

    private final OcpClientHolder ocp;
    private final DeploymentApplier deployments;
    private final JobApplier jobs;

    public OcpActivitiesImpl(OcpClientHolder ocp, DeploymentApplier deployments, JobApplier jobs) {
        this.ocp = ocp;
        this.deployments = deployments;
        this.jobs = jobs;
    }

    @Override
    public DeployResult applyService(ServiceDefinition def, List<ImageSpec> images) {
        var d = deployments.apply(def, images);
        return new DeployResult(d.getMetadata().getName(), ocp.namespace());
    }

    @Override
    public ReplicaStatus serviceReadyStatus(ComputeId id, Lane lane, String group, String project) {
        var r = deployments.readyStatus(id, new LaneRef(group, project, lane));
        return new ReplicaStatus(r.desired(), r.ready(), r.allReady(), r.imagePullError());
    }

    @Override
    public void scaleService(ComputeId id, Lane lane, String group, String project, int replicas) {
        deployments.scale(id, new LaneRef(group, project, lane), replicas);
    }

    @Override
    public void deleteService(ComputeId id, Lane lane, String group, String project) {
        deployments.delete(id, new LaneRef(group, project, lane));
    }

    @Override
    public JobResult applyOcpJob(JobDefinition def, List<ImageSpec> images,
                                 LocalDate runDate, Map<String, String> args) {
        var j = jobs.applyForJob(def, images, runDate, args);
        return new JobResult(j.getMetadata().getName(), ocp.namespace());
    }

    @Override
    public JobPoll pollOcpJob(String jobName) {
        return switch (jobs.poll(jobName)) {
            case PENDING   -> JobPoll.PENDING;
            case STARTING  -> JobPoll.STARTING;
            case RUNNING   -> JobPoll.RUNNING;
            case SUCCEEDED -> JobPoll.SUCCEEDED;
            case FAILED    -> JobPoll.FAILED;
        };
    }

    @Override
    public void deleteOcpJob(String jobName) {
        jobs.delete(jobName);
    }

    @Override
    public JobResult applyTaskWorkers(TaskRequest task, List<ImageSpec> images,
                                      String temporalTarget, String temporalNamespace) {
        var j = jobs.applyForTaskWorkers(task, images, temporalTarget, temporalNamespace);
        return new JobResult(j.getMetadata().getName(), ocp.namespace());
    }

    /** Keep ResourceNamer import meaningful so the impl can compose names if needed. */
    @SuppressWarnings("unused")
    private static String unused(ComputeId id) { return ResourceNamer.sanitize(id); }
}
