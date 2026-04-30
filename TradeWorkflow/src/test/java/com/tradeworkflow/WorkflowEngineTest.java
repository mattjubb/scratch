package com.tradeworkflow;

import com.tradeworkflow.engine.WorkflowEngine;
import com.tradeworkflow.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowEngineTest {

    private WorkflowEngine engine;
    private static final String WF_ID = "test-wf";

    @BeforeEach
    void setUp() {
        engine = WorkflowEngine.getInstance();
        engine.registerWorkflow(buildTestWorkflow());
    }

    // -------------------------------------------------------------------------
    // Workflow management
    // -------------------------------------------------------------------------

    @Test
    void workflowIsRegisteredAndRetrievable() {
        assertTrue(engine.getWorkflow(WF_ID).isPresent());
        assertEquals("Test Workflow", engine.getWorkflow(WF_ID).get().getName());
    }

    // -------------------------------------------------------------------------
    // Trade creation & initial state
    // -------------------------------------------------------------------------

    @Test
    void tradeStartsInInitialState() {
        Trade trade = engine.createTrade(WF_ID, Map.of());
        assertEquals("OPEN", trade.getCurrentState());
    }

    @Test
    void tradeAcceptsArbitraryInitialMetadata() {
        Trade trade = engine.createTrade(WF_ID, Map.of("foo", "bar"));
        assertEquals("OPEN", trade.getCurrentState());
        assertEquals("bar", trade.getMetadata().get("foo"));
    }

    // -------------------------------------------------------------------------
    // External event triggering
    // -------------------------------------------------------------------------

    @Test
    void externalDeclarativeEventTransitionsState() {
        Trade trade = engine.createTrade(WF_ID, Map.of());
        Trade updated = engine.triggerEvent(trade.getId(), "PROCESS", Collections.emptyMap());
        // PROCESSING auto-fires AUTO_COMPLETE → DONE
        assertEquals("DONE", updated.getCurrentState());
    }

    @Test
    void declarativeOutcomeAppliesMetadataMutations() {
        Trade trade = engine.createTrade(WF_ID, Map.of());
        engine.triggerEvent(trade.getId(), "PROCESS", Collections.emptyMap());
        Trade updated = engine.getTrade(trade.getId()).orElseThrow();
        assertNotNull(updated.getMetadata().get("processedAt"));
        assertNotNull(updated.getMetadata().get("completedAt"));
    }

    @Test
    void eventNotAvailableFromCurrentStateThrows() {
        Trade trade = engine.createTrade(WF_ID, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> engine.triggerEvent(trade.getId(), "AUTO_COMPLETE", Collections.emptyMap()));
    }

    // -------------------------------------------------------------------------
    // Branching: declarative 'when'
    // -------------------------------------------------------------------------

    @Test
    void declarativeWhenPicksMatchingOutcome() {
        // CHECK_AMOUNT: amount >= 100 → HIGH, else LOW
        Trade big = engine.createTrade(WF_ID, Map.of("amount", "500"));
        engine.triggerEvent(big.getId(), "CHECK_AMOUNT", Collections.emptyMap());
        assertEquals("HIGH", engine.getTrade(big.getId()).orElseThrow().getCurrentState());

        Trade small = engine.createTrade(WF_ID, Map.of("amount", "5"));
        engine.triggerEvent(small.getId(), "CHECK_AMOUNT", Collections.emptyMap());
        assertEquals("LOW", engine.getTrade(small.getId()).orElseThrow().getCurrentState());
    }

    // -------------------------------------------------------------------------
    // Branching: script return value
    // -------------------------------------------------------------------------

    @Test
    void javaScriptReturnedOutcomeChoosesBranch() {
        Trade ok = engine.createTrade(WF_ID, Map.of("amount", "100"));
        engine.triggerEvent(ok.getId(), "VALIDATE", Collections.emptyMap());
        assertEquals("VALIDATED", engine.getTrade(ok.getId()).orElseThrow().getCurrentState());

        Trade bad = engine.createTrade(WF_ID, Map.of("amount", "-1"));
        engine.triggerEvent(bad.getId(), "VALIDATE", Collections.emptyMap());
        assertEquals("REJECTED", engine.getTrade(bad.getId()).orElseThrow().getCurrentState());
    }

    @Test
    void javaScriptOutcomeIsRecordedInHistory() {
        Trade trade = engine.createTrade(WF_ID, Map.of("amount", "100"));
        engine.triggerEvent(trade.getId(), "VALIDATE", Collections.emptyMap());
        TradeHistoryEntry last = engine.getTrade(trade.getId()).orElseThrow()
                .getHistory().get(0);
        assertEquals("VALIDATE", last.getEventName());
        assertEquals("success", last.getOutcomeName());
    }

    // -------------------------------------------------------------------------
    // Available events
    // -------------------------------------------------------------------------

    @Test
    void availableEventsListsOnlyExternalEventsFromCurrentState() {
        Trade trade = engine.createTrade(WF_ID, Map.of());
        List<String> events = engine.getAvailableEvents(trade.getId());
        assertTrue(events.contains("PROCESS"));
        assertTrue(events.contains("CHECK_AMOUNT"));
        assertTrue(events.contains("VALIDATE"));
        assertFalse(events.contains("AUTO_COMPLETE")); // AUTO trigger
    }

    // -------------------------------------------------------------------------
    // YAML round-trip
    // -------------------------------------------------------------------------

    @Test
    void workflowYamlRoundTrip() throws Exception {
        String yaml = engine.exportWorkflowYaml(WF_ID);
        assertNotNull(yaml);
        assertTrue(yaml.contains("Test Workflow"));
        Workflow imported = engine.importWorkflowYaml(yaml);
        assertNotNull(imported.getId());
        assertEquals("OPEN", imported.getInitialState());
    }

    // -------------------------------------------------------------------------
    // Helper – build a test workflow demonstrating all branching styles
    // -------------------------------------------------------------------------

    private Workflow buildTestWorkflow() {
        // States: OPEN -> PROCESSING (auto -> DONE), OPEN -> HIGH | LOW (declarative when),
        //         OPEN -> VALIDATED | REJECTED (script-driven)
        WorkflowState open       = state("OPEN");
        WorkflowState processing = state("PROCESSING", List.of("AUTO_COMPLETE"));
        WorkflowState done       = state("DONE");
        WorkflowState high       = state("HIGH");
        WorkflowState low        = state("LOW");
        WorkflowState validated  = state("VALIDATED");
        WorkflowState rejected   = state("REJECTED");

        WorkflowEvent process = declarativeEvent("PROCESS", "OPEN", TriggerType.EXTERNAL,
                outcome("ok", "PROCESSING", null,
                        Map.of("processedAt", "${now}"), null));

        WorkflowEvent autoComplete = declarativeEvent(
                "AUTO_COMPLETE", "PROCESSING", TriggerType.AUTO,
                outcome("ok", "DONE", null,
                        Map.of("completedAt", "${now}"), null));

        WorkflowEvent checkAmount = declarativeEvent(
                "CHECK_AMOUNT", "OPEN", TriggerType.EXTERNAL,
                outcome("high", "HIGH", "amount >= 100", null, null),
                outcome("low",  "LOW",  null,             null, null));

        WorkflowEvent validate = scriptEvent("VALIDATE", "OPEN", TriggerType.EXTERNAL,
                TransitionType.JAVASCRIPT,
                "function execute(m) {"
              + "  var amount = parseFloat(m.amount || 0);"
              + "  if (amount > 0) { m.validatedAt = new Date().toISOString();"
              + "                    return { outcome: 'success', metadata: m }; }"
              + "  m.rejectedReason = 'invalid amount';"
              + "  return { outcome: 'failure', metadata: m };"
              + "}",
                outcome("success", "VALIDATED", null, null, null),
                outcome("failure", "REJECTED",  null, null, null));

        Workflow wf = new Workflow();
        wf.setId(WF_ID);
        wf.setName("Test Workflow");
        wf.setInitialState("OPEN");
        wf.setStates(List.of(open, processing, done, high, low, validated, rejected));
        wf.setEvents(List.of(process, autoComplete, checkAmount, validate));
        return wf;
    }

    private WorkflowState state(String name) {
        return state(name, Collections.emptyList());
    }

    private WorkflowState state(String name, List<String> autoEvents) {
        WorkflowState s = new WorkflowState();
        s.setName(name);
        s.setAutoEvents(autoEvents);
        return s;
    }

    private Outcome outcome(String name, String toState, String when,
                            Map<String, String> set, List<String> unset) {
        Outcome o = new Outcome();
        o.setName(name);
        o.setToState(toState);
        o.setWhen(when);
        o.setSet(set);
        o.setUnset(unset);
        return o;
    }

    private WorkflowEvent declarativeEvent(String name, String fromState, TriggerType trigger,
                                           Outcome... outcomes) {
        WorkflowEvent e = new WorkflowEvent();
        e.setName(name);
        e.setFromState(fromState);
        e.setTrigger(trigger);
        e.setType(TransitionType.DECLARATIVE);
        e.setOutcomes(new ArrayList<>(Arrays.asList(outcomes)));
        return e;
    }

    private WorkflowEvent scriptEvent(String name, String fromState, TriggerType trigger,
                                      TransitionType type, String script, Outcome... outcomes) {
        WorkflowEvent e = new WorkflowEvent();
        e.setName(name);
        e.setFromState(fromState);
        e.setTrigger(trigger);
        e.setType(type);
        e.setScript(script);
        e.setOutcomes(new ArrayList<>(Arrays.asList(outcomes)));
        return e;
    }
}
