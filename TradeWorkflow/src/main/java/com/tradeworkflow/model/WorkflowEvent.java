package com.tradeworkflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * An event (transition) in the workflow FSM. Moves a trade from {@link #fromState}
 * to one of its declared {@link #outcomes}, chosen either declaratively (via each
 * outcome's {@code when} predicate) or by a script that returns an outcome name.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowEvent {

    private String name;
    private String description;

    /** Source state this event can fire from. */
    private String fromState;

    private TriggerType trigger = TriggerType.EXTERNAL;

    private TransitionType type = TransitionType.DECLARATIVE;

    /** Script body, used when {@link #type} is JAVASCRIPT or PYTHON. */
    private String script;

    /**
     * The possible outcomes of this event. At least one is required; for a
     * deterministic transition declare a single outcome with no {@code when}.
     */
    private List<Outcome> outcomes = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getFromState() { return fromState; }
    public void setFromState(String fromState) { this.fromState = fromState; }

    public TriggerType getTrigger() { return trigger; }
    public void setTrigger(TriggerType trigger) { this.trigger = trigger; }

    public TransitionType getType() { return type; }
    public void setType(TransitionType type) { this.type = type; }

    public String getScript() { return script; }
    public void setScript(String script) { this.script = script; }

    public List<Outcome> getOutcomes() { return outcomes; }
    public void setOutcomes(List<Outcome> outcomes) { this.outcomes = outcomes; }
}
