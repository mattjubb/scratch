package io.acme.orchestrator.k8s;

import io.acme.orchestrator.dag.PendingRunRecord;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Short-lived ConfigMap-backed store for {@link PendingRunRecord}s.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>Engine puts a run into PENDING_DEPS → {@link #upsert(PendingRunRecord)}.</li>
 *   <li>Dependencies satisfy, run is submitted as a Kubernetes Job →
 *       {@link #delete(String, String)} (the Job resource is now authoritative).</li>
 *   <li>On cold start, {@link #list()} rehydrates the engine's PENDING_DEPS state.</li>
 * </ol>
 * Unlike {@link DefinitionStore} this does not run an informer — pending-run
 * state is only read at startup; during steady-state the engine owns it.
 */
public final class PendingRunStore {

    private static final Logger log = LoggerFactory.getLogger(PendingRunStore.class);

    private final KubernetesClient client;
    private final String namespace;

    public PendingRunStore(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    public void upsert(PendingRunRecord record) {
        String cmName = Labels.pendingRunCmName(record.logicalName(), record.runId());
        String json;
        try {
            json = Json.MAPPER.writeValueAsString(record);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot serialize pending run", e);
        }
        ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(cmName)
                    .withNamespace(namespace)
                    .addToLabels(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY)
                    .addToLabels(Labels.KEY_KIND, Labels.VAL_KIND_PENDING_RUN)
                    .addToLabels(Labels.KEY_LOGICAL_NAME, record.logicalName())
                    .addToLabels(Labels.KEY_RUN_ID, record.runId())
                .endMetadata()
                .withData(Map.of(Labels.CM_DATA_KEY, json))
                .build();
        client.configMaps().inNamespace(namespace).resource(cm).forceConflicts().serverSideApply();
        log.debug("persisted pending run {}#{}", record.logicalName(), record.runId());
    }

    public void delete(String logicalName, String runId) {
        String cmName = Labels.pendingRunCmName(logicalName, runId);
        client.configMaps().inNamespace(namespace).withName(cmName).delete();
        log.debug("cleared pending run {}#{}", logicalName, runId);
    }

    /** Delete every PENDING_DEPS CM for this logical job (called on unregister). */
    public void deleteAllFor(String logicalName) {
        client.configMaps().inNamespace(namespace)
                .withLabel(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY)
                .withLabel(Labels.KEY_KIND, Labels.VAL_KIND_PENDING_RUN)
                .withLabel(Labels.KEY_LOGICAL_NAME, logicalName)
                .delete();
    }

    /** Used on cold start to rehydrate engine state. */
    public List<PendingRunRecord> list() {
        List<PendingRunRecord> out = new ArrayList<>();
        var cms = client.configMaps().inNamespace(namespace)
                .withLabel(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY)
                .withLabel(Labels.KEY_KIND, Labels.VAL_KIND_PENDING_RUN)
                .list();
        for (ConfigMap cm : cms.getItems()) {
            String json = cm.getData() != null ? cm.getData().get(Labels.CM_DATA_KEY) : null;
            if (json == null) continue;
            try {
                out.add(Json.MAPPER.readValue(json, PendingRunRecord.class));
            } catch (Exception e) {
                log.error("malformed pending-run cm {}", cm.getMetadata().getName(), e);
            }
        }
        return out;
    }
}
