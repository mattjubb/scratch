package com.compute.ocp;

import com.compute.model.EnvVar;
import com.compute.model.ImageSpec;
import com.compute.model.ResourceSpec;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link PodSpec} used by every Vasara workload pod (services, jobs,
 * task workers).
 *
 * <p>Every pod gets a shared {@code emptyDir} volume named {@code ext}. One init
 * container per {@link ImageSpec} runs:</p>
 *
 * <pre>{@code
 * unzip -o -q /app/app.jar 'BOOT-INF/*' -d /tmp/x
 * mkdir -p /ext/classes /ext/lib
 * cp -rn /tmp/x/BOOT-INF/classes/. /ext/classes/ 2>/dev/null || true
 * cp -n  /tmp/x/BOOT-INF/lib/*.jar /ext/lib/        2>/dev/null || true
 * }</pre>
 *
 * <p>{@code cp -n} makes precedence deterministic — earlier init containers win for any
 * duplicate jar name; image {@code order} is preserved by {@link ResourceNamer}'s caller.</p>
 *
 * <p>The main container runs the configured workload runner image, which simply execs
 * {@code java -cp '/ext/classes:/ext/lib/*' MAIN_CLASS ARGS}.</p>
 */
public final class PodSpecComposer {

    /** Convention: every workload image stages its Spring Boot fat jar at this path. */
    public static final String APP_JAR_PATH = "/app/app.jar";

    /** Where the init container extracts BOOT-INF contents. */
    public static final String EXT_DIR = "/ext";

    public static final String EXT_VOLUME = "ext";

    /** Tiny JDK base image used by every main container. */
    public static final String DEFAULT_RUNNER_IMAGE = "registry.local/compute/workload-runner:1";

    private String runnerImage = DEFAULT_RUNNER_IMAGE;

    public PodSpecComposer runnerImage(String image) {
        this.runnerImage = image;
        return this;
    }

    public PodSpec compose(Params p) {
        Volume extVol = new Volume();
        extVol.setName(EXT_VOLUME);
        extVol.setEmptyDir(new EmptyDirVolumeSource());

        VolumeMount mount = new VolumeMount();
        mount.setName(EXT_VOLUME);
        mount.setMountPath(EXT_DIR);

        List<io.fabric8.kubernetes.api.model.Container> initContainers = new ArrayList<>();
        for (ImageSpec img : p.images) {
            initContainers.add(new ContainerBuilder()
                    .withName("extract-" + ResourceNamer.sanitize(img.name()))
                    .withImage(img.imageRef())
                    .withCommand("sh", "-c", extractScript())
                    .withVolumeMounts(mount)
                    .build());
        }

        VolumeMount mainMount = new VolumeMount();
        mainMount.setName(EXT_VOLUME);
        mainMount.setMountPath(EXT_DIR);
        mainMount.setReadOnly(true);

        List<String> mainCommand = List.of("sh", "-c", javaLaunch(p.mainClass, p.args));

        ContainerBuilder main = new ContainerBuilder()
                .withName("app")
                .withImage(runnerImage)
                .withCommand(mainCommand)
                .withVolumeMounts(mainMount);

        for (EnvVar e : p.env) {
            main.addNewEnv().withName(e.name()).withValue(e.value()).endEnv();
        }
        for (Map.Entry<String, String> e : p.extraEnv.entrySet()) {
            main.addNewEnv().withName(e.getKey()).withValue(e.getValue()).endEnv();
        }
        for (com.compute.model.PortSpec port : p.ports) {
            main.addNewPort()
                    .withName(port.name())
                    .withContainerPort(port.port())
                    .withProtocol(port.protocol())
                    .endPort();
        }

        ResourceRequirements req = new ResourceRequirements();
        req.setRequests(Map.of(
                "cpu", new Quantity(p.resources.cpu()),
                "memory", new Quantity(p.resources.memory())
        ));
        req.setLimits(Map.of(
                "cpu", new Quantity(p.resources.cpu()),
                "memory", new Quantity(p.resources.memory())
        ));
        main.withResources(req);

        // On a single-node CRC cluster the kubelet sometimes applies disk-pressure /
        // memory-pressure / pid-pressure taints to the only available node, which would
        // block all scheduling.  Tolerate those system-added taints so workloads can
        // still run in the dev environment.
        List<Toleration> tolerations = List.of(
                toleration("node.kubernetes.io/disk-pressure"),
                toleration("node.kubernetes.io/memory-pressure"),
                toleration("node.kubernetes.io/pid-pressure")
        );

        return new PodSpecBuilder()
                .withRestartPolicy(p.restartPolicy)
                .withVolumes(extVol)
                .withInitContainers(initContainers)
                .withContainers(main.build())
                .withTolerations(tolerations)
                .build();
    }

    private static Toleration toleration(String key) {
        return new TolerationBuilder()
                .withKey(key)
                .withOperator("Exists")
                .withEffect("NoSchedule")
                .build();
    }

    static String extractScript() {
        return "set -e; "
             + "unzip -o -q " + APP_JAR_PATH + " 'BOOT-INF/*' -d /tmp/x; "
             + "mkdir -p " + EXT_DIR + "/classes " + EXT_DIR + "/lib; "
             + "cp -rn /tmp/x/BOOT-INF/classes/. " + EXT_DIR + "/classes/ 2>/dev/null || true; "
             + "cp -n  /tmp/x/BOOT-INF/lib/*.jar " + EXT_DIR + "/lib/        2>/dev/null || true";
    }

    static String javaLaunch(String mainClass, List<String> args) {
        StringBuilder sb = new StringBuilder("exec java -cp '" + EXT_DIR + "/classes:" + EXT_DIR + "/lib/*' ");
        sb.append(mainClass);
        for (String a : args) {
            sb.append(' ').append(shellQuote(a));
        }
        return sb.toString();
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Inputs for {@link #compose}. */
    public static final class Params {
        public List<ImageSpec> images = List.of();
        public String mainClass = "";
        public List<String> args = List.of();
        public List<EnvVar> env = List.of();
        public Map<String, String> extraEnv = Map.of();
        public List<com.compute.model.PortSpec> ports = List.of();
        public ResourceSpec resources = ResourceSpec.defaults();
        public String restartPolicy = "Always";

        public Params images(List<ImageSpec> v) { this.images = v; return this; }
        public Params mainClass(String v) { this.mainClass = v; return this; }
        public Params args(List<String> v) { this.args = v; return this; }
        public Params env(List<EnvVar> v) { this.env = v; return this; }
        public Params extraEnv(Map<String, String> v) { this.extraEnv = v; return this; }
        public Params ports(List<com.compute.model.PortSpec> v) { this.ports = v; return this; }
        public Params resources(ResourceSpec v) { this.resources = v; return this; }
        public Params restartPolicy(String v) { this.restartPolicy = v; return this; }
    }
}
