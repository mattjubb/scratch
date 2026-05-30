package com.compute.ocp;

import com.compute.model.EnvVar;
import com.compute.model.ImageSpec;
import com.compute.model.PortSpec;
import com.compute.model.ResourceSpec;
import io.fabric8.kubernetes.api.model.PodSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PodSpecComposerTest {

    @Test
    void composesInitAndMainContainersWithSharedVolume() {
        PodSpec spec = new PodSpecComposer().compose(new PodSpecComposer.Params()
                .images(List.of(
                        new ImageSpec("core", "registry/core:1", 0),
                        new ImageSpec("ext", "registry/ext:1", 1)))
                .mainClass("com.example.MainApp")
                .args(List.of("--server.port=8080", "arg with spaces"))
                .env(List.of(new EnvVar("MODE", "live")))
                .extraEnv(Map.of("EXTRA", "1"))
                .ports(List.of(new PortSpec("rest", 8080)))
                .resources(new ResourceSpec("1", "2Gi"))
                .restartPolicy("Always"));

        assertThat(spec.getVolumes()).extracting("name").containsExactly("ext");
        assertThat(spec.getInitContainers()).hasSize(2);

        var firstInit = spec.getInitContainers().get(0);
        assertThat(firstInit.getName()).isEqualTo("extract-core");
        assertThat(firstInit.getImage()).isEqualTo("registry/core:1");
        assertThat(firstInit.getCommand()).contains("sh", "-c");
        assertThat(firstInit.getCommand().get(2))
                .contains("unzip -o -q /app/app.jar 'BOOT-INF/*'")
                .contains("/ext/classes")
                .contains("/ext/lib");

        var main = spec.getContainers().get(0);
        assertThat(main.getName()).isEqualTo("app");
        assertThat(main.getCommand().get(2))
                .contains("java -cp '/ext/classes:/ext/lib/*'")
                .contains("com.example.MainApp")
                .contains("'arg with spaces'");

        assertThat(main.getEnv()).extracting("name").contains("MODE", "EXTRA");
        assertThat(main.getPorts()).hasSize(1);
        assertThat(main.getVolumeMounts().get(0).getReadOnly()).isTrue();
        assertThat(spec.getRestartPolicy()).isEqualTo("Always");
    }

    @Test
    void extractScriptContainsUnzipAndCp() {
        String script = PodSpecComposer.extractScript();
        assertThat(script).contains("unzip").contains("BOOT-INF").contains("cp -rn").contains("cp -n");
    }

    @Test
    void javaLaunchIncludesMainClassAndArgs() {
        String cmd = PodSpecComposer.javaLaunch("com.example.App", List.of("--port=8080", "hello world"));
        assertThat(cmd).contains("com.example.App").contains("--port=8080").contains("'hello world'");
    }

    @Test
    void javaLaunchHandlesSingleQuotesInArgs() {
        String cmd = PodSpecComposer.javaLaunch("com.App", List.of("it's a test"));
        assertThat(cmd).contains("it'\\''s a test");
    }

    @Test
    void customRunnerImageIsUsed() {
        PodSpec spec = new PodSpecComposer()
                .runnerImage("my-runner:latest")
                .compose(new PodSpecComposer.Params()
                        .images(List.of(new ImageSpec("x", "img:1", 0)))
                        .mainClass("com.Main")
                        .restartPolicy("Never"));
        assertThat(spec.getContainers().get(0).getImage()).isEqualTo("my-runner:latest");
    }

    @Test
    void tolerationsIncludeDiskAndMemoryPressure() {
        PodSpec spec = new PodSpecComposer().compose(new PodSpecComposer.Params()
                .images(List.of(new ImageSpec("x", "img:1", 0)))
                .mainClass("com.Main"));
        var tolerationKeys = spec.getTolerations().stream()
                .map(t -> t.getKey()).toList();
        assertThat(tolerationKeys).contains(
                "node.kubernetes.io/disk-pressure",
                "node.kubernetes.io/memory-pressure",
                "node.kubernetes.io/pid-pressure");
    }

    @Test
    void resourcesSetOnMainContainer() {
        PodSpec spec = new PodSpecComposer().compose(new PodSpecComposer.Params()
                .images(List.of(new ImageSpec("x", "img:1", 0)))
                .mainClass("com.Main")
                .resources(new ResourceSpec("2", "4Gi")));
        var reqs = spec.getContainers().get(0).getResources().getRequests();
        assertThat(reqs.get("cpu").toString()).contains("2");
        assertThat(reqs.get("memory").toString()).contains("4Gi");
    }

    @Test
    void emptyImagesProducesNoInitContainers() {
        PodSpec spec = new PodSpecComposer().compose(new PodSpecComposer.Params()
                .images(List.of())
                .mainClass("com.Main"));
        assertThat(spec.getInitContainers()).isEmpty();
    }
}
