package com.compute.temporal;

/**
 * Temporal connection + behavior configuration. Built from env vars:
 *
 * <ul>
 *   <li>{@code TEMPORAL_TARGET} — {@code host:port} (default {@code 127.0.0.1:7233})</li>
 *   <li>{@code TEMPORAL_SYSTEM_NAMESPACE} — namespace for the scheduler (default {@code system})</li>
 *   <li>{@code TEMPORAL_AUTO_CREATE_NAMESPACES} — {@code true}/{@code false}</li>
 * </ul>
 */
public record TemporalConfig(
        String target,
        String systemNamespace,
        boolean autoCreateNamespaces
) {
    public static TemporalConfig fromEnv() {
        return new TemporalConfig(
                envOr("TEMPORAL_TARGET", "127.0.0.1:7233"),
                envOr("TEMPORAL_SYSTEM_NAMESPACE", "vasara-system"),
                Boolean.parseBoolean(envOr("TEMPORAL_AUTO_CREATE_NAMESPACES", "true"))
        );
    }

    private static String envOr(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
