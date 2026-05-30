package com.compute.temporal;

import com.compute.model.LaneRef;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.namespace.v1.NamespaceConfig;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import com.google.protobuf.util.Durations;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures the Temporal namespace for a given (group, project, lane) exists. Caches
 * the per-namespace existence check so the hot path is just a {@link java.util.Set}
 * lookup.
 */
public final class NamespaceResolver {

    private static final Logger log = LoggerFactory.getLogger(NamespaceResolver.class);

    private final WorkflowServiceStubs stubs;
    private final boolean autoCreate;
    private final Set<String> known = ConcurrentHashMap.newKeySet();

    public NamespaceResolver(WorkflowServiceStubs stubs, boolean autoCreate) {
        this.stubs = stubs;
        this.autoCreate = autoCreate;
    }

    public String ensure(LaneRef ref) {
        return ensure(ref.temporalNamespace());
    }

    public String ensure(String namespace) {
        if (known.contains(namespace)) return namespace;
        if (exists(namespace)) {
            known.add(namespace);
            return namespace;
        }
        // describeNamespace may be forbidden on managed clusters; attempt registration
        // (ALREADY_EXISTS is handled gracefully in register()).
        if (autoCreate) {
            register(namespace);
        } else {
            throw new IllegalStateException("Temporal namespace not found and auto-create disabled: " + namespace);
        }
        known.add(namespace);
        return namespace;
    }

    private boolean exists(String namespace) {
        try {
            stubs.blockingStub().describeNamespace(
                    DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void register(String namespace) {
        log.info("registering Temporal namespace {}", namespace);
        try {
            stubs.blockingStub().registerNamespace(RegisterNamespaceRequest.newBuilder()
                    .setNamespace(namespace)
                    .setDescription("Vasara namespace for " + namespace)
                    .setWorkflowExecutionRetentionPeriod(Durations.fromDays(7))
                    .build());
        } catch (StatusRuntimeException e) {
            // ALREADY_EXISTS is fine — namespace exists (race with another instance, or pre-existing).
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                log.info("Temporal namespace {} already exists — continuing", namespace);
                return;
            }
            throw new RuntimeException("failed to register namespace " + namespace, e);
        } catch (Exception e) {
            throw new RuntimeException("failed to register namespace " + namespace, e);
        }
    }

    /** Pre-populate the cache with namespaces we already verified or registered. */
    public void prime(String namespace) {
        known.add(namespace);
    }

    /** Build a NamespaceConfig stub — kept to avoid an unused-import warning. */
    @SuppressWarnings("unused")
    private NamespaceConfig configStub() { return NamespaceConfig.newBuilder().build(); }
}
