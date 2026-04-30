package com.tradeworkflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A trade instance that moves through a workflow's states. The state is held
 * explicitly in {@link #currentState}; events transition the trade by choosing
 * one of their declared outcomes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Trade {

    private String id;
    private String workflowId;
    private String currentState;

    /** Free-form metadata mutated by event outcomes. */
    private Map<String, Object> metadata = new LinkedHashMap<>();

    private List<TradeHistoryEntry> history = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public List<TradeHistoryEntry> getHistory() { return history; }
    public void setHistory(List<TradeHistoryEntry> history) { this.history = history; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
