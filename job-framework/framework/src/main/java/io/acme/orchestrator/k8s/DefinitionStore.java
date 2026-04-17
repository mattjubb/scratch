package io.acme.orchestrator.k8s;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ConfigMap-backed persistent store for a definition type (Job or Service).
 * <p>
 * Design:
 * <ul>
 *   <li>One ConfigMap per definition, name {@code jobdef-&lt;name&gt;} or
 *       {@code svcdef-&lt;name&gt;}, data key {@code spec.json}.</li>
 *   <li>Writes go through {@code upsert()}; the store is the single place
 *       that knows the CM format.</li>
 *   <li>A {@link SharedIndexInformer} watches CMs with the matching kind
 *       label. Every add/update/delete becomes a callback. The API verticle
 *       NEVER mutates the engine directly — it only writes CMs, and the
 *       informer is the single writer to the engine's state. This means
 *       cold-start recovery and steady-state registration take the same
 *       code path.</li>
 * </ul>
 * The informer's initial LIST replays every definition on startup before
 * {@link SharedIndexInformer#hasSynced()} returns true — call {@link #awaitSync()}
 * after {@link #start()} if you need to block on it.
 *
 * @param <T> the definition type (e.g. {@code JobDefinition})
 */
public final class DefinitionStore<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefinitionStore.class);

    private final KubernetesClient client;
    private final Vertx vertx;
    private final String namespace;
    private final String kindLabelValue;
    private final Class<T> type;
    private final java.util.function.Function<T, String> nameExtractor;
    private final java.util.function.Function<String, String> cmNameFn;

    private BiConsumer<String, T> onUpsert = (n, t) -> {};
    private Consumer<String> onDelete = n -> {};

    private SharedIndexInformer<ConfigMap> informer;

    public DefinitionStore(KubernetesClient client,
                           Vertx vertx,
                           String namespace,
                           String kindLabelValue,
                           Class<T> type,
                           java.util.function.Function<T, String> nameExtractor,
                           java.util.function.Function<String, String> cmNameFn) {
        this.client = client;
        this.vertx = vertx;
        this.namespace = namespace;
        this.kindLabelValue = kindLabelValue;
        this.type = type;
        this.nameExtractor = nameExtractor;
        this.cmNameFn = cmNameFn;
    }

    public void onUpsert(BiConsumer<String, T> handler)   { this.onUpsert = handler; }
    public void onDelete(Consumer<String> handler)        { this.onDelete = handler; }

    /** Write or replace the definition's CM. Returns the new resourceVersion. */
    public void upsert(T definition) {
        String name = nameExtractor.apply(definition);
        String cmName = cmNameFn.apply(name);
        String json;
        try {
            json = Json.MAPPER.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize definition " + name, e);
        }
        ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(cmName)
                    .withNamespace(namespace)
                    .addToLabels(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY)
                    .addToLabels(Labels.KEY_KIND, kindLabelValue)
                    .addToLabels(Labels.KEY_LOGICAL_NAME, name)
                .endMetadata()
                .withData(Map.of(Labels.CM_DATA_KEY, json))
                .build();
        client.configMaps().inNamespace(namespace).resource(cm).forceConflicts().serverSideApply();
        log.info("upsert {} cm={}", kindLabelValue, cmName);
    }

    public void delete(String logicalName) {
        String cmName = cmNameFn.apply(logicalName);
        client.configMaps().inNamespace(namespace).withName(cmName).delete();
        log.info("delete {} cm={}", kindLabelValue, cmName);
    }

    public void start() {
        informer = client.configMaps()
                .inNamespace(namespace)
                .withLabel(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY)
                .withLabel(Labels.KEY_KIND, kindLabelValue)
                .inform(new ResourceEventHandler<>() {
                    @Override public void onAdd(ConfigMap cm)                      { dispatchUpsert(cm); }
                    @Override public void onUpdate(ConfigMap oldCm, ConfigMap cm)  { dispatchUpsert(cm); }
                    @Override public void onDelete(ConfigMap cm, boolean finalState) { dispatchDelete(cm); }
                });
        log.info("definition-store informer started for {}", kindLabelValue);
    }

    /** Block until the informer has finished its initial LIST. */
    public void awaitSync() {
        if (informer == null) return;
        while (!informer.hasSynced()) {
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
        }
    }

    private void dispatchUpsert(ConfigMap cm) {
        String json = cm.getData() != null ? cm.getData().get(Labels.CM_DATA_KEY) : null;
        if (json == null) {
            log.warn("cm {} has no {} key; skipping", cm.getMetadata().getName(), Labels.CM_DATA_KEY);
            return;
        }
        T def;
        try {
            def = Json.MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.error("cm {} has malformed payload", cm.getMetadata().getName(), e);
            return;
        }
        String name = nameExtractor.apply(def);
        vertx.runOnContext(v -> onUpsert.accept(name, def));
    }

    private void dispatchDelete(ConfigMap cm) {
        String name = cm.getMetadata().getLabels() != null
                ? cm.getMetadata().getLabels().get(Labels.KEY_LOGICAL_NAME)
                : null;
        if (name == null) return;
        vertx.runOnContext(v -> onDelete.accept(name));
    }

    @Override
    public void close() {
        if (informer != null) informer.close();
    }
}
