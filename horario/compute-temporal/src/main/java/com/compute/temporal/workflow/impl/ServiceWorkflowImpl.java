package com.compute.temporal.workflow.impl;

import com.compute.model.ImageSpec;
import com.compute.model.ServiceDefinition;
import com.compute.model.ServiceState;
import com.compute.model.ServiceStatus;
import com.compute.temporal.activity.ImageActivities;
import com.compute.temporal.activity.OcpActivities;
import com.compute.temporal.workflow.ServiceWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;

public final class ServiceWorkflowImpl implements ServiceWorkflow {

    private final ImageActivities images = Workflow.newActivityStub(
            ImageActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
                    .build());

    private final OcpActivities ocp = Workflow.newActivityStub(
            OcpActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    private ServiceDefinition def;
    private ServiceState state;
    private boolean stopRequested;
    private boolean restartRequested;
    private boolean iceRequested;
    private boolean startRequested;
    private boolean redeployRequested;

    @Override
    public void run(ServiceDefinition definition) {
        this.def = definition;
        this.state = ServiceState.initial(definition);
        deploy();

        while (!stopRequested) {
            Workflow.await(Duration.ofMinutes(1),
                    () -> stopRequested || restartRequested || iceRequested
                            || startRequested || redeployRequested);

            if (stopRequested) break;
            if (iceRequested) { doIce(); iceRequested = false; }
            if (restartRequested) { doRestart(); restartRequested = false; }
            if (startRequested) { start0(); startRequested = false; }
            if (redeployRequested) { deploy(); redeployRequested = false; }

            // Periodic health refresh (skip if FAILED — await restart/redeploy signal)
            if (state.status() != ServiceStatus.FAILED) refreshReadyStatus();
        }

        // Tear down
        state = state.withStatus(ServiceStatus.STOPPING, "stop signal");
        try {
            ocp.deleteService(def.id(), def.lane(), def.group(), def.project());
        } catch (Exception e) {
            Workflow.getLogger(ServiceWorkflowImpl.class).warn(
                    "deleteService failed (continuing): {}", e.getMessage());
        }
        state = state.withStatus(ServiceStatus.STOPPED, "deleted");
    }

    private void deploy() {
        state = state.withStatus(ServiceStatus.STARTING, "fetching images");
        try {
            List<ImageSpec> imgs = images.fetch(def.group(), def.project(), def.lane(), def.version());
            state = state.withImages(imgs);
            ocp.applyService(def, imgs);
            waitReady();
        } catch (Exception e) {
            // OCP or image errors — transition to FAILED; workflow stays alive for restart/redeploy signals
            state = state.withStatus(ServiceStatus.FAILED,
                    "deploy failed: " + truncate(e.getMessage(), 200));
        }
    }

    private void start0() {
        try {
            ocp.scaleService(def.id(), def.lane(), def.group(), def.project(), def.replicas());
            state = state.withStatus(ServiceStatus.STARTING, "starting");
            waitReady();
        } catch (Exception e) {
            state = state.withStatus(ServiceStatus.FAILED,
                    "start failed: " + truncate(e.getMessage(), 200));
        }
    }

    private void doIce() {
        try {
            ocp.scaleService(def.id(), def.lane(), def.group(), def.project(), 0);
            state = state.withStatus(ServiceStatus.ICED, "iced — replicas=0");
        } catch (Exception e) {
            state = state.withStatus(ServiceStatus.DEGRADED,
                    "ice failed: " + truncate(e.getMessage(), 200));
        }
    }

    private void doRestart() {
        try {
            ocp.scaleService(def.id(), def.lane(), def.group(), def.project(), 0);
            Workflow.sleep(Duration.ofSeconds(3));
            ocp.scaleService(def.id(), def.lane(), def.group(), def.project(), def.replicas());
            state = state.withStatus(ServiceStatus.STARTING, "restarting");
            waitReady();
        } catch (Exception e) {
            state = state.withStatus(ServiceStatus.FAILED,
                    "restart failed: " + truncate(e.getMessage(), 200));
        }
    }

    /**
     * Polls the OCP Deployment for readiness. Returns as soon as all replicas are ready,
     * transitions to FAILED on persistent image-pull errors, or DEGRADED after overall timeout.
     *
     * <p>Version 1: adds per-iteration signal check (10 s interrupt window).</p>
     * <p>Version 2: adds image-pull-error detection. On {@code ImagePullBackOff} /
     * {@code ErrImagePull}, retries up to 3 times with a 1-minute gap between checks.
     * After 3 consecutive failures the service transitions to FAILED so the operator
     * knows to fix the image reference rather than waiting for a 10-minute timeout.</p>
     */
    private void waitReady() {
        final int v = Workflow.getVersion("waitReady-interruptible", Workflow.DEFAULT_VERSION, 2);
        int imagePullFailures = 0;
        for (int i = 0; i < 60; i++) {
            if (v >= 1 && (stopRequested || redeployRequested || iceRequested
                    || restartRequested || startRequested)) return;
            OcpActivities.ReplicaStatus r = ocp.serviceReadyStatus(
                    def.id(), def.lane(), def.group(), def.project());
            state = state.withReplicas(r.desired(), r.ready());
            if (r.allReady()) {
                state = state.withStatus(ServiceStatus.RUNNING, "all replicas ready");
                return;
            }
            if (v >= 2 && r.imagePullError()) {
                imagePullFailures++;
                state = state.withStatus(ServiceStatus.STARTING,
                        "image pull error (attempt " + imagePullFailures + "/3)");
                if (imagePullFailures >= 3) {
                    state = state.withStatus(ServiceStatus.FAILED,
                            "image pull failed after 3 attempts — check image registry");
                    return;
                }
                Workflow.sleep(Duration.ofMinutes(1));
            } else {
                imagePullFailures = 0; // reset counter if pod recovers
                Workflow.sleep(Duration.ofSeconds(10));
            }
        }
        state = state.withStatus(ServiceStatus.DEGRADED, "ready check timed out");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void refreshReadyStatus() {
        if (state.status() == ServiceStatus.ICED || state.status() == ServiceStatus.STOPPED) return;
        OcpActivities.ReplicaStatus r = ocp.serviceReadyStatus(
                def.id(), def.lane(), def.group(), def.project());
        state = state.withReplicas(r.desired(), r.ready());
        // Degrade when: replicas not ready, OR deployment is missing (desired=0 but definition expects some).
        // The `r.desired() > 0` guard was previously masking the missing-deployment case.
        boolean deploymentDown = !r.allReady() || (r.desired() == 0 && def.replicas() > 0);
        if (state.status() == ServiceStatus.RUNNING && deploymentDown) {
            state = state.withStatus(ServiceStatus.DEGRADED, "replicas not ready");
        } else if (state.status() == ServiceStatus.DEGRADED && r.allReady()) {
            state = state.withStatus(ServiceStatus.RUNNING, "recovered");
        }
    }

    @Override public void start() { startRequested = true; }
    @Override public void stop() { stopRequested = true; }
    @Override public void restart() { restartRequested = true; }
    @Override public void ice() { iceRequested = true; }
    @Override public void redeploy(ServiceDefinition newDefinition) {
        this.def = newDefinition;
        this.redeployRequested = true;
    }
    @Override public ServiceState getState() { return state; }
}
