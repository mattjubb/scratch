package com.compute.ocp;

import com.compute.model.ComputeId;
import com.compute.model.ImageSpec;
import com.compute.model.LaneRef;
import com.compute.model.ServiceDefinition;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates / updates / scales OCP Deployments for services.
 */
public final class DeploymentApplier {

    private static final Logger log = LoggerFactory.getLogger(DeploymentApplier.class);

    private final OcpClientHolder ocp;
    private final PodSpecComposer composer;

    public DeploymentApplier(OcpClientHolder ocp, PodSpecComposer composer) {
        this.ocp = ocp;
        this.composer = composer;
    }

    public Deployment apply(ServiceDefinition def, List<ImageSpec> images) {
        String name = ResourceNamer.deploymentName(def.id(), def.laneRef());
        Map<String, String> labels = labels(def);

        PodSpec podSpec = composer.compose(new PodSpecComposer.Params()
                .images(images)
                .mainClass(def.mainClass())
                .args(def.args())
                .env(def.env())
                .ports(def.ports())
                .resources(def.resources())
                .restartPolicy("Always"));

        Deployment d = new DeploymentBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(ocp.namespace())
                        .withLabels(labels)
                        .build())
                .withNewSpec()
                    .withReplicas(def.replicas())
                    .withSelector(new LabelSelectorBuilder().withMatchLabels(Map.of(
                            ResourceNamer.LABEL_ID, ResourceNamer.sanitize(def.id())
                    )).build())
                    .withNewTemplate()
                        .withMetadata(new ObjectMetaBuilder().withLabels(labels).build())
                        .withSpec(podSpec)
                    .endTemplate()
                .endSpec()
                .build();

        if (ocp.isDryRun()) {
            log.info("[dry-run] would apply Deployment {} (replicas={})", name, def.replicas());
            return d;
        }
        log.info("apply Deployment {} (replicas={})", name, def.replicas());
        return ocp.client().apps().deployments().inNamespace(ocp.namespace()).resource(d).serverSideApply();
    }

    public void scale(ComputeId id, LaneRef ref, int replicas) {
        String name = ResourceNamer.deploymentName(id, ref);
        if (ocp.isDryRun()) {
            log.info("[dry-run] would scale {} to {}", name, replicas);
            return;
        }
        ocp.client().apps().deployments().inNamespace(ocp.namespace())
                .withName(name).scale(replicas);
    }

    public void delete(ComputeId id, LaneRef ref) {
        String name = ResourceNamer.deploymentName(id, ref);
        if (ocp.isDryRun()) {
            log.info("[dry-run] would delete Deployment {}", name);
            return;
        }
        ocp.client().apps().deployments().inNamespace(ocp.namespace())
                .withName(name).delete();
    }

    public ReadyStatus readyStatus(ComputeId id, LaneRef ref) {
        // In dry-run there is no real Deployment, but the service is conceptually up.
        // Return (1, 1, true) so workflows see a plausible running state instead of 0 replicas.
        if (ocp.isDryRun()) return new ReadyStatus(1, 1, true, false);
        String name = ResourceNamer.deploymentName(id, ref);
        Deployment d = ocp.client().apps().deployments().inNamespace(ocp.namespace())
                .withName(name).get();
        if (d == null || d.getStatus() == null) return new ReadyStatus(0, 0, false, false);
        int desired = d.getSpec().getReplicas() == null ? 0 : d.getSpec().getReplicas();
        int ready = d.getStatus().getReadyReplicas() == null ? 0 : d.getStatus().getReadyReplicas();
        boolean imagePullError = hasImagePullError(id);
        return new ReadyStatus(desired, ready, ready == desired && desired > 0, imagePullError);
    }

    /**
     * Returns {@code true} if any container (init or main) in any pod belonging to this
     * service's deployment is stuck in {@code ImagePullBackOff} or {@code ErrImagePull}.
     */
    private boolean hasImagePullError(ComputeId id) {
        try {
            var pods = ocp.client().pods()
                    .inNamespace(ocp.namespace())
                    .withLabel(ResourceNamer.LABEL_ID, ResourceNamer.sanitize(id))
                    .list().getItems();
            for (var pod : pods) {
                var status = pod.getStatus();
                if (status == null) continue;
                for (var cs : nullSafe(status.getInitContainerStatuses())) {
                    if (isImagePullError(cs)) return true;
                }
                for (var cs : nullSafe(status.getContainerStatuses())) {
                    if (isImagePullError(cs)) return true;
                }
            }
        } catch (Exception e) {
            log.debug("hasImagePullError check failed for {}: {}", id, e.getMessage());
        }
        return false;
    }

    private static boolean isImagePullError(io.fabric8.kubernetes.api.model.ContainerStatus cs) {
        if (cs == null || cs.getState() == null || cs.getState().getWaiting() == null) return false;
        String r = cs.getState().getWaiting().getReason();
        return "ImagePullBackOff".equals(r) || "ErrImagePull".equals(r);
    }

    private static <T> java.util.List<T> nullSafe(java.util.List<T> list) {
        return list != null ? list : java.util.List.of();
    }

    private Map<String, String> labels(ServiceDefinition def) {
        Map<String, String> m = new LinkedHashMap<>(ResourceNamer.baseLabels(def.laneRef()));
        m.put(ResourceNamer.LABEL_KIND, "service");
        m.put(ResourceNamer.LABEL_ID, ResourceNamer.sanitize(def.id()));
        m.put(ResourceNamer.LABEL_VERSION, def.version());
        return m;
    }

    public record ReadyStatus(int desired, int ready, boolean allReady, boolean imagePullError) {}
}
