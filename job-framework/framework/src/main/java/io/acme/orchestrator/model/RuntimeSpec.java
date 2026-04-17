package io.acme.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Things common to both Jobs and Services:
 * runtime image, user-visible main class, JVM args, env, resources.
 * <p>
 * The {@code runtimeImage} is the minimal Java base (e.g. ubi9/openjdk-21-runtime)
 * that provides the JVM. The user code itself arrives via the {@code codebase}
 * layers and lands on the classpath at {@code /opt/app/classpath/*.jar}.
 */
public record RuntimeSpec(
        @JsonProperty("runtimeImage") String runtimeImage,
        @JsonProperty("mainClass") String mainClass,
        @JsonProperty("codebase") List<CodebaseLayer> codebase,
        @JsonProperty("args") List<String> args,
        @JsonProperty("jvmArgs") List<String> jvmArgs,
        @JsonProperty("env") Map<String, String> env,
        @JsonProperty("resources") Resources resources
) {
    @JsonCreator
    public RuntimeSpec {
        if (runtimeImage == null || runtimeImage.isBlank()) {
            throw new IllegalArgumentException("runtimeImage required");
        }
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException("mainClass required");
        }
        codebase = codebase == null ? List.of() : List.copyOf(codebase);
        args = args == null ? List.of() : List.copyOf(args);
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        env = env == null ? Map.of() : Map.copyOf(env);
        if (resources == null) resources = Resources.defaults();
    }

    public record Resources(
            @JsonProperty("cpuRequest") String cpuRequest,
            @JsonProperty("cpuLimit") String cpuLimit,
            @JsonProperty("memRequest") String memRequest,
            @JsonProperty("memLimit") String memLimit
    ) {
        public static Resources defaults() {
            return new Resources("250m", "1", "512Mi", "1Gi");
        }
    }
}
