package com.compute.ocp;

import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the Fabric8 client (or null in dry-run mode). The OCP namespace defaults to
 * {@code vasara} and is overridable via {@code COMPUTE_OCP_NAMESPACE}.
 */
public final class OcpClientHolder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OcpClientHolder.class);

    private final OpenShiftClient client;
    private final boolean dryRun;
    private final String namespace;

    public OcpClientHolder(boolean dryRun, String namespace) {
        this.dryRun = dryRun;
        this.namespace = namespace == null || namespace.isBlank() ? "vasara" : namespace;
        if (dryRun) {
            log.info("OCP client in DRY-RUN mode (namespace={})", this.namespace);
            this.client = null;
        } else {
            this.client = new KubernetesClientBuilder().build().adapt(OpenShiftClient.class);
            log.info("OCP client connected (master={}, namespace={})",
                    client.getMasterUrl(), this.namespace);
        }
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public String namespace() {
        return namespace;
    }

    public OpenShiftClient client() {
        if (dryRun) throw new IllegalStateException("OCP client unavailable in dry-run mode");
        return client;
    }

    @Override
    public void close() {
        if (client != null) client.close();
    }
}
