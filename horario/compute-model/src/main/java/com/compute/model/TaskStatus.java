package com.compute.model;

public enum TaskStatus {
    PENDING, PROVISIONING_WORKERS, RUNNING, COMPLETED, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
