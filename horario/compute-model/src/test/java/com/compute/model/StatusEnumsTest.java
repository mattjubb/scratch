package com.compute.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers JobStatus, ServiceStatus and TaskStatus enum behaviour.
 */
class StatusEnumsTest {

    // ── JobStatus ──────────────────────────────────────────────────────────────

    @Test
    void jobStatusTerminalValues() {
        assertThat(JobStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(JobStatus.FAILED.isTerminal()).isTrue();
        assertThat(JobStatus.SKIPPED.isTerminal()).isTrue();
    }

    @Test
    void jobStatusNonTerminalValues() {
        assertThat(JobStatus.SCHEDULED.isTerminal()).isFalse();
        assertThat(JobStatus.WAITING_DEPS.isTerminal()).isFalse();
        assertThat(JobStatus.STARTING.isTerminal()).isFalse();
        assertThat(JobStatus.RUNNING.isTerminal()).isFalse();
    }

    // ── ServiceStatus ──────────────────────────────────────────────────────────

    @Test
    void serviceStatusTerminalValues() {
        assertThat(ServiceStatus.STOPPED.isTerminal()).isTrue();
        assertThat(ServiceStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    void serviceStatusNonTerminalValues() {
        assertThat(ServiceStatus.STARTING.isTerminal()).isFalse();
        assertThat(ServiceStatus.RUNNING.isTerminal()).isFalse();
        assertThat(ServiceStatus.DEGRADED.isTerminal()).isFalse();
        assertThat(ServiceStatus.ICED.isTerminal()).isFalse();
        assertThat(ServiceStatus.STOPPING.isTerminal()).isFalse();
    }

    @Test
    void serviceStatusActiveValues() {
        assertThat(ServiceStatus.STARTING.isActive()).isTrue();
        assertThat(ServiceStatus.RUNNING.isActive()).isTrue();
        assertThat(ServiceStatus.DEGRADED.isActive()).isTrue();
        assertThat(ServiceStatus.STOPPING.isActive()).isTrue();
    }

    @Test
    void serviceStatusInactiveValues() {
        assertThat(ServiceStatus.STOPPED.isActive()).isFalse();
        assertThat(ServiceStatus.FAILED.isActive()).isFalse();
        assertThat(ServiceStatus.ICED.isActive()).isFalse();
    }

    // ── TaskStatus ─────────────────────────────────────────────────────────────

    @Test
    void taskStatusTerminalValues() {
        assertThat(TaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    void taskStatusNonTerminalValues() {
        assertThat(TaskStatus.PENDING.isTerminal()).isFalse();
        assertThat(TaskStatus.PROVISIONING_WORKERS.isTerminal()).isFalse();
        assertThat(TaskStatus.RUNNING.isTerminal()).isFalse();
    }
}
