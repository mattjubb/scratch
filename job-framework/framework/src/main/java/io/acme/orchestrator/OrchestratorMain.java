package io.acme.orchestrator;

import io.acme.orchestrator.dag.DependencyEngine;
import io.acme.orchestrator.dag.PendingRunRecord;
import io.acme.orchestrator.k8s.DefinitionStore;
import io.acme.orchestrator.k8s.JobInformer;
import io.acme.orchestrator.k8s.Labels;
import io.acme.orchestrator.k8s.PendingRunStore;
import io.acme.orchestrator.k8s.ResourceBuilders;
import io.acme.orchestrator.model.JobDefinition;
import io.acme.orchestrator.model.ServiceDefinition;
import io.acme.orchestrator.scheduling.SchedulerVerticle;
import io.acme.orchestrator.server.ApiVerticle;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Orchestrator process entry point.
 * <p>
 * Startup ordering is load-bearing:
 * <ol>
 *   <li>Wire engine hooks to persistent stores.</li>
 *   <li>Start definition informers and await their initial LIST. Every
 *       registered Job/Service definition is now in the engine.</li>
 *   <li>Start the Job informer. Its initial LIST replays terminal state
 *       from the cluster into the engine (cold-start recovery).</li>
 *   <li>Rehydrate PENDING_DEPS runs from their CMs, discarding any whose
 *       age exceeds a staleness threshold. Rehydration re-evaluates
 *       against the terminal state loaded in step 3, so any pending run
 *       whose upstream has since succeeded will fire immediately.</li>
 *   <li>Deploy the scheduler and HTTP API verticles.</li>
 * </ol>
 * Deviating from this order breaks recovery: for example, if pending runs
 * are rehydrated before terminal state is loaded, every pending run will
 * see its dependencies as unsatisfied and sit waiting.
 */
public final class OrchestratorMain {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorMain.class);

    /** Pending runs older than this on cold start are discarded. */
    private static final Duration PENDING_STALENESS = Duration.ofHours(48);

    public static void main(String[] args) {
        String namespace = System.getenv().getOrDefault("ORCHESTRATOR_NAMESPACE", "default");
        int port = Integer.parseInt(System.getenv().getOrDefault("ORCHESTRATOR_PORT", "8080"));

        Vertx vertx = Vertx.vertx();
        KubernetesClient client = new KubernetesClientBuilder().build();
        DependencyEngine engine = new DependencyEngine();
        SchedulerVerticle scheduler = new SchedulerVerticle(engine);

        DefinitionStore<JobDefinition> jobStore = new DefinitionStore<>(
                client, vertx, namespace,
                Labels.VAL_KIND_JOB_DEF, JobDefinition.class,
                JobDefinition::name, Labels::jobDefCmName);
        DefinitionStore<ServiceDefinition> serviceStore = new DefinitionStore<>(
                client, vertx, namespace,
                Labels.VAL_KIND_SERVICE_DEF, ServiceDefinition.class,
                ServiceDefinition::name, Labels::serviceDefCmName);
        PendingRunStore pendingRunStore = new PendingRunStore(client, namespace);

        wireEngineHooks(client, engine, pendingRunStore);
        wireDefinitionStoreHooks(jobStore, serviceStore, engine, scheduler, client, namespace);

        // Step 2: load definitions first. Blocking is OK — we're in main().
        jobStore.start();
        serviceStore.start();
        jobStore.awaitSync();
        serviceStore.awaitSync();
        log.info("loaded {} job defs, {} service defs",
                engine.all().size(),
                "?"); // engine only tracks jobs; services are reconciled directly

        // Step 3: start the Job informer so terminal state is populated.
        JobInformer jobInformer = new JobInformer(client, vertx, engine, namespace);
        jobInformer.start();
        // No explicit await here — the informer drives itself; pending-run
        // rehydration will see whatever's arrived by the time scheduleFire
        // is invoked. Terminal events that land AFTER rehydration still
        // trigger the normal downstream cascade.

        // Step 4: rehydrate pending runs.
        rehydratePending(vertx, pendingRunStore, engine);

        // Step 5: deploy verticles.
        vertx.deployVerticle(scheduler);
        vertx.deployVerticle(new ApiVerticle(
                engine, jobStore, serviceStore, pendingRunStore, port));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            jobInformer.close();
            jobStore.close();
            serviceStore.close();
            vertx.close();
            client.close();
        }));

        log.info("orchestrator up, namespace={}, port={}", namespace, port);
    }

    // ---- hook wiring -------------------------------------------------------

    private static void wireEngineHooks(KubernetesClient client,
                                        DependencyEngine engine,
                                        PendingRunStore pendingRunStore) {
        // Engine wants to fire → create Job in cluster, mark submitted.
        engine.onReadyToFire((def, runId) -> {
            Instant fireTime = engine.snapshot().get(def.name()).fireTime();
            var job = ResourceBuilders.buildJob(def, runId, fireTime);
            try {
                client.batch().v1().jobs()
                        .inNamespace(def.namespace())
                        .resource(job)
                        .create();
                engine.markSubmitted(def.name(), runId);
                log.info("submitted job {} run {}", def.name(), runId);
            } catch (Exception e) {
                log.error("failed to submit job {}", def.name(), e);
                // Leave the pending CM in place; operator can inspect or retrigger.
            }
        });

        // Engine entered PENDING_DEPS → persist so the intent survives restart.
        engine.onPendingRunCreated((def, rs) ->
                pendingRunStore.upsert(new PendingRunRecord(
                        rs.logicalName(), rs.runId(), rs.fireTime(), Instant.now())));

        // Engine resolved a pending run (submitted / terminal / unregistered)
        // → clear the CM. Kubernetes Job / definition CM is now authoritative.
        engine.onPendingRunResolved(pendingRunStore::delete);
    }

    private static void wireDefinitionStoreHooks(
            DefinitionStore<JobDefinition> jobStore,
            DefinitionStore<ServiceDefinition> serviceStore,
            DependencyEngine engine,
            SchedulerVerticle scheduler,
            KubernetesClient client,
            String orchestratorNamespace) {

        // Job definition CM add/update → engine + scheduler.
        // We also validate that the def targets our own namespace, since
        // our RBAC is a Role (namespaced), not a ClusterRole.
        jobStore.onUpsert((name, def) -> {
            if (!def.namespace().equals(orchestratorNamespace)) {
                log.error("rejecting job {}: namespace {} does not match orchestrator namespace {}",
                        name, def.namespace(), orchestratorNamespace);
                return;
            }
            engine.register(def);
            scheduler.track(def);
        });
        jobStore.onDelete(name -> {
            engine.unregister(name);
            scheduler.untrack(name);
        });

        // Service definition CM add/update → reconcile Deployment + Service.
        // No engine involvement — services don't participate in the DAG.
        serviceStore.onUpsert((name, def) -> {
            if (!def.namespace().equals(orchestratorNamespace)) {
                log.error("rejecting service {}: namespace {} does not match orchestrator namespace {}",
                        name, def.namespace(), orchestratorNamespace);
                return;
            }
            reconcileService(client, def);
        });
        serviceStore.onDelete(name -> {
            // Target namespace is always ours — service defs are constrained above.
            client.apps().deployments()
                    .inNamespace(orchestratorNamespace)
                    .withLabel(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY)
                    .withLabel(Labels.KEY_KIND, Labels.VAL_KIND_SVC)
                    .withLabel(Labels.KEY_LOGICAL_NAME, name)
                    .delete();
            client.services()
                    .inNamespace(orchestratorNamespace)
                    .withLabel(Labels.KEY_MANAGED_BY, Labels.VAL_MANAGED_BY)
                    .withLabel(Labels.KEY_LOGICAL_NAME, name)
                    .delete();
        });
    }

    private static void reconcileService(KubernetesClient client, ServiceDefinition def) {
        try {
            client.apps().deployments()
                    .inNamespace(def.namespace())
                    .resource(ResourceBuilders.buildDeployment(def))
                    .forceConflicts().serverSideApply();
            var svc = ResourceBuilders.buildService(def);
            if (svc != null) {
                client.services()
                        .inNamespace(def.namespace())
                        .resource(svc)
                        .forceConflicts().serverSideApply();
            }
            log.info("reconciled service {}", def.name());
        } catch (Exception e) {
            log.error("service reconcile failed for {}", def.name(), e);
        }
    }

    private static void rehydratePending(Vertx vertx,
                                         PendingRunStore store,
                                         DependencyEngine engine) {
        Instant cutoff = Instant.now().minus(PENDING_STALENESS);
        int loaded = 0, stale = 0;
        for (PendingRunRecord rec : store.list()) {
            if (rec.createdAt().isBefore(cutoff)) {
                store.delete(rec.logicalName(), rec.runId());
                stale++;
                continue;
            }
            vertx.runOnContext(v ->
                    engine.rehydratePending(rec.logicalName(), rec.runId(), rec.fireTime()));
            loaded++;
        }
        log.info("rehydrated {} pending runs ({} stale discarded)", loaded, stale);
    }
}
