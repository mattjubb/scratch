package io.acme.orchestrator.k8s;

import io.acme.orchestrator.model.JobDefinition;
import io.acme.orchestrator.model.ServiceDefinition;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns {@link JobDefinition} and {@link ServiceDefinition} into concrete
 * fabric8 resources ready for {@code create()}.
 */
public final class ResourceBuilders {

    private ResourceBuilders() {}

    /**
     * Build a {@link Job} for a single firing of {@code def}.
     *
     * @param runId     opaque unique id for this firing (used in name + label)
     * @param fireTime  nominal scheduled time; stored as an annotation for audit
     */
    public static Job buildJob(JobDefinition def, String runId, Instant fireTime) {
        String jobName = def.name() + "-" + runId;

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY);
        labels.put(Labels.KEY_KIND, Labels.VAL_KIND_JOB);
        labels.put(Labels.KEY_LOGICAL_NAME, def.name());
        labels.put(Labels.KEY_RUN_ID, runId);

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(Labels.ANN_FIRE_TIME, fireTime.toString());
        if (!def.dependencies().isEmpty()) {
            annotations.put(Labels.ANN_DEPENDENCIES, String.join(",", def.dependencies()));
        }

        PodSpec podSpec = PodSpecBuilder.buildPodSpec(def.runtime(), def.name());

        return new JobBuilder()
                .withNewMetadata()
                    .withName(jobName)
                    .withNamespace(def.namespace())
                    .withLabels(labels)
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(def.backoffLimit())
                    .withActiveDeadlineSeconds(def.activeDeadline().toSeconds())
                    .withTtlSecondsAfterFinished(60 * 60 * 24) // GC after 24h
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                            .withAnnotations(annotations)
                        .endMetadata()
                        .withSpec(podSpec)
                    .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * Build the long-lived {@link Deployment} for a service.
     * A sibling {@link io.fabric8.kubernetes.api.model.Service} is produced
     * via {@link #buildService(ServiceDefinition)} when an httpPort is set.
     */
    public static Deployment buildDeployment(ServiceDefinition def) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY);
        labels.put(Labels.KEY_KIND, Labels.VAL_KIND_SVC);
        labels.put(Labels.KEY_LOGICAL_NAME, def.name());

        PodSpec podSpec = PodSpecBuilder.buildPodSpecForDeployment(def.runtime(), def.name());

        // Wire probes if the service opted into HTTP.
        if (def.httpPort() != null) {
            Container main = podSpec.getContainers().get(0);
            main.setPorts(java.util.List.of(
                    new ContainerPortBuilder().withName("http").withContainerPort(def.httpPort()).build()
            ));
            main.setReadinessProbe(httpProbe(def.readinessPath(), def.httpPort(), 5, 10));
            main.setLivenessProbe(httpProbe(def.livenessPath(), def.httpPort(), 15, 20));
        }

        return new DeploymentBuilder()
                .withNewMetadata()
                    .withName(def.name())
                    .withNamespace(def.namespace())
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(def.replicas())
                    .withNewSelector()
                        .addToMatchLabels(Labels.KEY_LOGICAL_NAME, def.name())
                        .addToMatchLabels(Labels.KEY_KIND, Labels.VAL_KIND_SVC)
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata().withLabels(labels).endMetadata()
                        .withSpec(podSpec)
                    .endTemplate()
                .endSpec()
                .build();
    }

    /** Optional headless-ish Service (ClusterIP) in front of a service Deployment. */
    public static io.fabric8.kubernetes.api.model.Service buildService(ServiceDefinition def) {
        if (def.httpPort() == null) return null;
        Map<String, String> selector = Map.of(
                Labels.KEY_LOGICAL_NAME, def.name(),
                Labels.KEY_KIND, Labels.VAL_KIND_SVC
        );
        return new ServiceBuilder()
                .withNewMetadata()
                    .withName(def.name())
                    .withNamespace(def.namespace())
                    .addToLabels(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY)
                .endMetadata()
                .withNewSpec()
                    .withSelector(selector)
                    .addNewPort()
                        .withName("http")
                        .withPort(def.httpPort())
                        .withTargetPort(new IntOrString(def.httpPort()))
                    .endPort()
                .endSpec()
                .build();
    }

    private static Probe httpProbe(String path, int port, int initialDelay, int period) {
        return new ProbeBuilder()
                .withNewHttpGet()
                    .withPath(path)
                    .withPort(new IntOrString(port))
                .endHttpGet()
                .withInitialDelaySeconds(initialDelay)
                .withPeriodSeconds(period)
                .build();
    }
}
