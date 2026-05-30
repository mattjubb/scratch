package com.compute.cp;

import java.nio.file.Path;
import java.nio.file.Paths;

public record ControlPlaneConfig(
        int httpPort,
        Path definitionsDir,
        String imageVersionApiBaseUrl,
        String ocpNamespace,
        boolean dryRun
) {
    public static ControlPlaneConfig fromEnv() {
        return new ControlPlaneConfig(
                Integer.parseInt(envOr("HTTP_PORT", "8080")),
                Paths.get(envOr("COMPUTE_DEFINITIONS_DIR", "deploy/definitions")),
                envOr("IMAGE_VERSION_API_URL", ""),
                envOr("COMPUTE_OCP_NAMESPACE", "vasara"),
                Boolean.parseBoolean(envOr("COMPUTE_DRY_RUN", "false"))
        );
    }

    private static String envOr(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
