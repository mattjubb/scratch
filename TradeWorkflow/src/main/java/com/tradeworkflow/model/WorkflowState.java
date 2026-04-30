package com.tradeworkflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A state in a workflow FSM. A trade's state is set explicitly (the workflow's
 * {@code initialState} on creation, or an event's chosen outcome's {@code toState}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowState {

    private String name;
    private String description;

    /**
     * Names of AUTO events to fire immediately upon entering this state.
     * Processed in order; stops after the first event that causes a state change.
     */
    private List<String> autoEvents = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getAutoEvents() { return autoEvents; }
    public void setAutoEvents(List<String> autoEvents) { this.autoEvents = autoEvents; }
}
