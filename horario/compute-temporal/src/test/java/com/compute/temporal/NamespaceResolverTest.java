package com.compute.temporal;

import com.compute.model.Lane;
import com.compute.model.LaneRef;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class NamespaceResolverTest {

    private TestWorkflowEnvironment testEnv;
    private WorkflowServiceStubs stubs;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        testEnv.start();
        stubs = testEnv.getWorkflowClient().getWorkflowServiceStubs();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void primeCachesNamespaceAndSkipsRemoteCheck() {
        NamespaceResolver resolver = new NamespaceResolver(stubs, false);
        resolver.prime("rates-swaps-dev");
        // Should return without hitting remote (which would fail for unknown namespace)
        String ns = resolver.ensure("rates-swaps-dev");
        assertThat(ns).isEqualTo("rates-swaps-dev");
    }

    @Test
    void ensureWithLaneRefDelegatesToNamespaceString() {
        NamespaceResolver resolver = new NamespaceResolver(stubs, false);
        LaneRef ref = new LaneRef("rates", "swaps", Lane.DEV);
        resolver.prime(ref.temporalNamespace());
        String ns = resolver.ensure(ref);
        assertThat(ns).isEqualTo("rates-swaps-dev");
    }

    @Test
    void ensureDefaultNamespaceExistsOnTestServer() {
        // "default" namespace is pre-created by TestWorkflowEnvironment
        NamespaceResolver resolver = new NamespaceResolver(stubs, false);
        String ns = resolver.ensure("default");
        assertThat(ns).isEqualTo("default");
    }

    @Test
    void ensureCachesAfterFirstLookup() {
        NamespaceResolver resolver = new NamespaceResolver(stubs, false);
        resolver.ensure("default");
        // Second call hits cache (fast path) — should not throw
        String ns = resolver.ensure("default");
        assertThat(ns).isEqualTo("default");
    }

    @Test
    void autoCreateTrueRegistersNamespace() {
        NamespaceResolver resolver = new NamespaceResolver(stubs, true);
        // On the test server, registerNamespace is available
        String ns = resolver.ensure("my-new-namespace");
        assertThat(ns).isEqualTo("my-new-namespace");
    }

    @Test
    void autoCreateTrueIdempotentForAlreadyExistingNamespace() {
        NamespaceResolver resolver = new NamespaceResolver(stubs, true);
        resolver.ensure("default"); // already exists
        // Should not throw — ALREADY_EXISTS is handled gracefully
        String ns = resolver.ensure("default");
        assertThat(ns).isEqualTo("default");
    }
}
