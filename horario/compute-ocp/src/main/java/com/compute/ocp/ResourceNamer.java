package com.compute.ocp;

import com.compute.model.ComputeId;
import com.compute.model.LaneRef;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates compute identifiers into Kubernetes-safe names and the standard label set
 * applied to every Vasara-managed object.
 */
public final class ResourceNamer {

    /** Domain prefix for all framework labels. */
    public static final String LABEL_PREFIX = "vasara.compute";

    public static final String LABEL_KIND = LABEL_PREFIX + "/kind";
    public static final String LABEL_ID = LABEL_PREFIX + "/id";
    public static final String LABEL_GROUP = LABEL_PREFIX + "/group";
    public static final String LABEL_PROJECT = LABEL_PREFIX + "/project";
    public static final String LABEL_LANE = LABEL_PREFIX + "/lane";
    public static final String LABEL_VERSION = LABEL_PREFIX + "/version";
    public static final String LABEL_TASK_ID = LABEL_PREFIX + "/task-id";
    public static final String LABEL_RUN_DATE = LABEL_PREFIX + "/run-date";

    private ResourceNamer() {}

    /** {@code /rates/swaps/pricer} → {@code rates-swaps-pricer}. */
    public static String sanitize(ComputeId id) {
        return sanitize(id.path());
    }

    public static String sanitize(String raw) {
        String s = raw.startsWith("/") ? raw.substring(1) : raw;
        s = s.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        s = s.replaceAll("-+", "-");
        if (s.length() > 53) s = s.substring(0, 53);
        return s;
    }

    /** Stable name for a Service Deployment: {@code svc-{kind-id}-{lane}}. */
    public static String deploymentName(ComputeId id, LaneRef ref) {
        return clamp("svc-" + sanitize(id) + "-" + ref.lane().code());
    }

    /** Stable name for a per-day Job: {@code job-{id}-{yyyymmdd}}. */
    public static String jobName(ComputeId id, String yyyymmdd) {
        return clamp("job-" + sanitize(id) + "-" + yyyymmdd);
    }

    /** Per-task ephemeral worker Job name. */
    public static String taskJobName(String taskId) {
        return clamp("task-" + taskId.replace("-", ""));
    }

    private static String clamp(String s) {
        return s.length() > 63 ? s.substring(0, 63) : s;
    }

    public static Map<String, String> baseLabels(LaneRef ref) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(LABEL_GROUP, ref.group());
        m.put(LABEL_PROJECT, ref.project());
        m.put(LABEL_LANE, ref.lane().code());
        return m;
    }
}
