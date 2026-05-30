package com.compute.model;

public enum ServiceStatus {
    STARTING, RUNNING, DEGRADED, STOPPING, STOPPED, ICED, FAILED;

    public boolean isTerminal() {
        return this == STOPPED || this == FAILED;
    }

    public boolean isActive() {
        return this == STARTING || this == RUNNING || this == DEGRADED || this == STOPPING;
    }
}
