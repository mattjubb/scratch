package com.compute.yaml;

import com.compute.model.ComputeId;
import com.compute.model.EnvVar;
import com.compute.model.JobDefinition;
import com.compute.model.Lane;
import com.compute.model.PortSpec;
import com.compute.model.ResourceSpec;
import com.compute.model.ServiceDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Walks a definitions tree of the form:
 *
 * <pre>
 * {root}/services/{group}/{project}/.../name.yaml   → service id /{group}/{project}/.../name
 * {root}/jobs/{group}/{project}/.../name.yaml       → job id     /{group}/{project}/.../name
 * </pre>
 *
 * The id is purely derived from the path under the kind subdirectory; the YAML body
 * provides every other field. {@code group} and {@code project} fields in the YAML are
 * cross-checked against the first two path segments — a mismatch is an error.
 */
public final class DefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(DefinitionLoader.class);

    private final Path root;
    private final Lane lane;

    public DefinitionLoader(Path root, Lane lane) {
        this.root = root;
        this.lane = lane;
    }

    public Result load() {
        Path servicesDir = root.resolve("services");
        Path jobsDir = root.resolve("jobs");
        List<ServiceDefinition> services = walk(servicesDir, this::parseService);
        List<JobDefinition> jobs = walk(jobsDir, this::parseJob);
        log.info("loaded {} services and {} jobs from {}", services.size(), jobs.size(), root);
        return new Result(services, jobs);
    }

    /** Returns only services and jobs belonging to the given group/project. */
    public Result loadForProject(String group, String project) {
        Result all = load();
        List<ServiceDefinition> svcs = all.services().stream()
                .filter(s -> group.equals(s.group()) && project.equals(s.project()))
                .toList();
        List<JobDefinition> jobs = all.jobs().stream()
                .filter(j -> group.equals(j.group()) && project.equals(j.project()))
                .toList();
        return new Result(svcs, jobs);
    }

    private <T> List<T> walk(Path dir, BiParser<T> parser) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".yaml") || n.endsWith(".yml");
                    })
                    .sorted()
                    .forEach(p -> {
                        ComputeId id = ComputeId.fromRelativePath(dir.relativize(p));
                        try {
                            out.add(parser.parse(id, p));
                        } catch (Exception e) {
                            throw new DefinitionParseException(p, e);
                        }
                    });
        } catch (IOException e) {
            throw new DefinitionParseException(dir, e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private ServiceDefinition parseService(ComputeId id, Path file) throws IOException {
        Map<String, Object> raw = readYaml(file);
        crossCheckGroupProject(id, raw, file);
        return new ServiceDefinition(
                id,
                lane,
                str(raw, "group"),
                str(raw, "project"),
                str(raw, "mainClass"),
                strOr(raw, "version", lane.code()),
                intOr(raw, "replicas", 1),
                resources((Map<String, Object>) raw.get("resources")),
                envList((List<Map<String, Object>>) raw.get("env")),
                portList((List<Map<String, Object>>) raw.get("ports")),
                (List<String>) raw.getOrDefault("args", List.of())
        );
    }

    @SuppressWarnings("unchecked")
    private JobDefinition parseJob(ComputeId id, Path file) throws IOException {
        Map<String, Object> raw = readYaml(file);
        crossCheckGroupProject(id, raw, file);
        boolean scheduler = boolOr(raw, "scheduler", false);

        List<String> deps = (List<String>) raw.getOrDefault("deps", List.of());
        List<ComputeId> depIds = new ArrayList<>();
        for (String d : deps) depIds.add(ComputeId.of(d));

        Map<String, Object> defaults = (Map<String, Object>) raw.getOrDefault("defaults", Map.of());
        Map<String, Object> args = (Map<String, Object>) defaults.getOrDefault("args", Map.of());
        Map<String, String> defaultArgs = new LinkedHashMap<>();
        args.forEach((k, v) -> defaultArgs.put(k, v == null ? "" : v.toString()));

        // mainClass is required for regular jobs but optional for scheduler jobs.
        String mainClass = scheduler ? strOr(raw, "mainClass", "") : str(raw, "mainClass");

        return new JobDefinition(
                id,
                lane,
                str(raw, "group"),
                str(raw, "project"),
                mainClass,
                strOr(raw, "version", lane.code()),
                strOr(raw, "schedule", ""),
                Collections.unmodifiableList(depIds),
                resources((Map<String, Object>) raw.get("resources")),
                envList((List<Map<String, Object>>) raw.get("env")),
                Collections.unmodifiableMap(defaultArgs),
                scheduler,
                intOr(raw, "lookaheadDays", 2)
        );
    }

    private static void crossCheckGroupProject(ComputeId id, Map<String, Object> raw, Path file) {
        List<String> segs = id.segments();
        String yamlGroup = str(raw, "group");
        if (!segs.isEmpty() && !segs.get(0).equals(yamlGroup)) {
            throw new IllegalStateException(file + ": YAML group '" + yamlGroup
                    + "' doesn't match path segment '" + segs.get(0) + "'");
        }
        if (segs.size() >= 2) {
            String yamlProject = str(raw, "project");
            if (!segs.get(1).equals(yamlProject)) {
                throw new IllegalStateException(file + ": YAML project '" + yamlProject
                        + "' doesn't match path segment '" + segs.get(1) + "'");
            }
        }
    }

    private static Map<String, Object> readYaml(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (loaded == null) return Map.of();
            if (!(loaded instanceof Map)) {
                throw new IllegalStateException(file + ": expected a YAML mapping at the root");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) loaded;
            return m;
        }
    }

    private static ResourceSpec resources(Map<String, Object> r) {
        if (r == null) return ResourceSpec.defaults();
        return new ResourceSpec(
                String.valueOf(r.getOrDefault("cpu", "500m")),
                String.valueOf(r.getOrDefault("memory", "1Gi"))
        );
    }

    private static List<EnvVar> envList(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<EnvVar> out = new ArrayList<>(raw.size());
        for (Map<String, Object> e : raw) {
            out.add(new EnvVar(String.valueOf(e.get("name")), String.valueOf(e.getOrDefault("value", ""))));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<PortSpec> portList(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<PortSpec> out = new ArrayList<>(raw.size());
        for (Map<String, Object> p : raw) {
            String name = String.valueOf(p.get("name"));
            int port = ((Number) p.get("port")).intValue();
            String proto = String.valueOf(p.getOrDefault("protocol", "TCP"));
            out.add(new PortSpec(name, port, proto));
        }
        return Collections.unmodifiableList(out);
    }

    private static String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v == null) throw new IllegalStateException("missing required field: " + key);
        return v.toString();
    }

    private static String strOr(Map<String, Object> raw, String key, String fallback) {
        Object v = raw.get(key);
        return v == null ? fallback : v.toString();
    }

    private static int intOr(Map<String, Object> raw, String key, int fallback) {
        Object v = raw.get(key);
        return v == null ? fallback : ((Number) v).intValue();
    }

    private static boolean boolOr(Map<String, Object> raw, String key, boolean fallback) {
        Object v = raw.get(key);
        return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }

    @FunctionalInterface
    private interface BiParser<T> {
        T parse(ComputeId id, Path file) throws IOException;
    }

    public record Result(List<ServiceDefinition> services, List<JobDefinition> jobs) {}
}
