package com.tradeworkflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TradeHistoryEntry {

    private Instant timestamp;
    private String eventName;
    private String outcomeName;
    private String fromState;
    private String toState;
    private Map<String, Object> metadataSnapshot;

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getOutcomeName() { return outcomeName; }
    public void setOutcomeName(String outcomeName) { this.outcomeName = outcomeName; }

    public String getFromState() { return fromState; }
    public void setFromState(String fromState) { this.fromState = fromState; }

    public String getToState() { return toState; }
    public void setToState(String toState) { this.toState = toState; }

    public Map<String, Object> getMetadataSnapshot() { return metadataSnapshot; }
    public void setMetadataSnapshot(Map<String, Object> metadataSnapshot) { this.metadataSnapshot = metadataSnapshot; }
}
