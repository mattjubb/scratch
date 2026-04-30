package com.tradeworkflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level workflow definition: a named FSM consisting of states and events.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Workflow {

    private String id;
    private String name;
    private String description;

    /** Name of the state every new trade in this workflow starts in. */
    private String initialState;

    private List<WorkflowState> states = new ArrayList<>();
    private List<WorkflowEvent> events = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getInitialState() { return initialState; }
    public void setInitialState(String initialState) { this.initialState = initialState; }

    public List<WorkflowState> getStates() { return states; }
    public void setStates(List<WorkflowState> states) { this.states = states; }

    public List<WorkflowEvent> getEvents() { return events; }
    public void setEvents(List<WorkflowEvent> events) { this.events = events; }
}
