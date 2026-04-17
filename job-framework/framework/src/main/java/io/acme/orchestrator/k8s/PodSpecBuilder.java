package io.acme.orchestrator.k8s;

import io.acme.orchestrator.model.CodebaseLayer;
import io.acme.orchestrator.model.RuntimeSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link PodSpec} shared by Jobs and Deployments.
 * <p>
 * The classpath-assembly contract:
 * <pre>
 *   emptyDir "classpath"  ──mounted at /opt/app/classpath in every container──
 *
 *   init container N (layer N image):   cp /app/lib/*.jar /opt/app/classpath/
 *   init container N-1 (layer N-1 image): cp /app/lib/*.jar /opt/app/classpath/
 *   ...
 *   main container (runtime image):     exec java -cp '/opt/app/classpath/*' mainClass
 * </pre>
 * Layer ordering matters: later layers can override earlier ones because
 * {@code cp -f} overwrites. We preserve user-supplied ordering.
 */
final class PodSpecBuilder {

    private PodSpecBuilder() {}

    static PodSpec buildPodSpec(RuntimeSpec runtime, String containerName) {
        return buildPodSpec(runtime, containerName, "OnFailure");
    }

    static PodSpec buildPodSpecForDeployment(RuntimeSpec runtime, String containerName) {
        // Deployment pods require restartPolicy=Always.
        return buildPodSpec(runtime, containerName, "Always");
    }

    private static PodSpec buildPodSpec(RuntimeSpec runtime,
                                        String containerName,
                                        String restartPolicy) {
        Volume classpathVol = new VolumeBuilder()
                .withName(Labels.CLASSPATH_VOLUME)
                .withNewEmptyDir().endEmptyDir()
                .build();

        VolumeMount classpathMount = new VolumeMountBuilder()
                .withName(Labels.CLASSPATH_VOLUME)
                .withMountPath(Labels.CLASSPATH_MOUNT)
                .build();

        List<Container> initContainers = new ArrayList<>();
        for (CodebaseLayer layer : runtime.codebase()) {
            initContainers.add(initContainerFor(layer, classpathMount));
        }

        Container main = mainContainer(runtime, containerName, classpathMount);

        return new io.fabric8.kubernetes.api.model.PodSpecBuilder()
                .withRestartPolicy(restartPolicy)
                .withInitContainers(initContainers)
                .withContainers(List.of(main))
                .withVolumes(List.of(classpathVol))
                .build();
    }

    private static Container initContainerFor(CodebaseLayer layer, VolumeMount mount) {
        // Use /bin/sh + cp; every UBI-micro or busybox-based layer image has both.
        // -f so later layers can overwrite earlier ones.
        String cmd = "set -eu; "
                + "echo 'staging layer " + layer.name() + "'; "
                + "mkdir -p " + Labels.CLASSPATH_MOUNT + "; "
                + "cp -f " + layer.sourcePath() + "/*.jar " + Labels.CLASSPATH_MOUNT + "/ || "
                + "  { echo 'no jars found in " + layer.sourcePath() + "'; exit 1; }";

        return new ContainerBuilder()
                .withName("stage-" + layer.name())
                .withImage(layer.image())
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/sh", "-c", cmd)
                .withVolumeMounts(mount)
                .build();
    }

    private static Container mainContainer(RuntimeSpec runtime,
                                           String containerName,
                                           VolumeMount mount) {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.addAll(runtime.jvmArgs());
        command.add("-cp");
        command.add(Labels.CLASSPATH_MOUNT + "/*");
        command.add(runtime.mainClass());

        List<EnvVar> envVars = new ArrayList<>();
        Map<String, String> env = new LinkedHashMap<>(runtime.env());
        env.forEach((k, v) -> envVars.add(new EnvVarBuilder().withName(k).withValue(v).build()));

        ResourceRequirements res = new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity(runtime.resources().cpuRequest()))
                .addToRequests("memory", new Quantity(runtime.resources().memRequest()))
                .addToLimits("cpu", new Quantity(runtime.resources().cpuLimit()))
                .addToLimits("memory", new Quantity(runtime.resources().memLimit()))
                .build();

        return new ContainerBuilder()
                .withName(containerName)
                .withImage(runtime.runtimeImage())
                .withImagePullPolicy("IfNotPresent")
                .withCommand(command)
                .withArgs(runtime.args())
                .withEnv(envVars)
                .withResources(res)
                .withVolumeMounts(mount)
                .build();
    }
}
