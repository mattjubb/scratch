package com.tradeworkflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One possible result of an event. An event always lists the outcomes it can
 * produce; the engine picks one (declaratively via {@link #when} or via a script's
 * returned outcome name) and moves the trade to {@link #toState}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Outcome {

    /** Stable name used by scripts to reference this outcome (e.g. "success", "failure"). */
    private String name;

    private String description;

    /** Target state this outcome moves the trade to. */
    private String toState;

    /**
     * Optional declarative predicate. Format: {@code <key> <op> <literal>} where
     * op ∈ {==, !=, &gt;, &gt;=, &lt;, &lt;=}. Numeric and string literals supported.
     * If {@code null} the outcome always matches (acts as the default branch).
     */
    private String when;

    /** Declarative metadata mutations applied if this outcome is chosen. */
    private Map<String, String> set;

    /** Metadata keys to remove if this outcome is chosen. */
    private List<String> unset;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getToState() { return toState; }
    public void setToState(String toState) { this.toState = toState; }

    public String getWhen() { return when; }
    public void setWhen(String when) { this.when = when; }

    public Map<String, String> getSet() { return set; }
    public void setSet(Map<String, String> set) { this.set = set; }

    public List<String> getUnset() { return unset; }
    public void setUnset(List<String> unset) { this.unset = unset; }
}
