package com.compute.ocp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcpClientHolderTest {

    @Test
    void dryRunModeDoesNotCreateClient() {
        OcpClientHolder h = new OcpClientHolder(true, "vasara");
        assertThat(h.isDryRun()).isTrue();
        assertThat(h.namespace()).isEqualTo("vasara");
    }

    @Test
    void clientThrowsInDryRunMode() {
        OcpClientHolder h = new OcpClientHolder(true, "vasara");
        assertThatThrownBy(h::client)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dry-run");
    }

    @Test
    void nullNamespaceDefaultsToVasara() {
        OcpClientHolder h = new OcpClientHolder(true, null);
        assertThat(h.namespace()).isEqualTo("vasara");
    }

    @Test
    void blankNamespaceDefaultsToVasara() {
        OcpClientHolder h = new OcpClientHolder(true, "   ");
        assertThat(h.namespace()).isEqualTo("vasara");
    }

    @Test
    void customNamespaceIsPreserved() {
        OcpClientHolder h = new OcpClientHolder(true, "my-ns");
        assertThat(h.namespace()).isEqualTo("my-ns");
    }

    @Test
    void closingDryRunHolderDoesNotThrow() {
        OcpClientHolder h = new OcpClientHolder(true, "vasara");
        h.close(); // should not throw
    }
}
