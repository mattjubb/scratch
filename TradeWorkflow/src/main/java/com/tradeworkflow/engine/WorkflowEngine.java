package com.tradeworkflow.engine;

import com.tradeworkflow.loader.WorkflowLoader;
import com.tradeworkflow.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Core workflow engine: manages workflow definitions and trade instances,
 * resolves event outcomes, and applies state transitions.
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final WorkflowEngine INSTANCE = new WorkflowEngine();

    private static final int MAX_AUTO_EVENT_DEPTH = 20;

    private final Map<String, Workflow> workflows = new ConcurrentHashMap<>();
    private final Map<String, Trade>    trades    = new ConcurrentHashMap<>();
    private final ScriptExecutor scriptExecutor = new ScriptExecutor();
    private final WorkflowLoader loader = new WorkflowLoader();

    private WorkflowEngine() {
        loadBuiltinWorkflows();
    }

    public static WorkflowEngine getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Workflow management
    // -------------------------------------------------------------------------

    public Workflow registerWorkflow(Workflow workflow) {
        if (workflow.getId() == null || workflow.getId().isBlank()) {
            workflow.setId(UUID.randomUUID().toString());
        }
        workflows.put(workflow.getId(), workflow);
        log.info("Registered workflow '{}' (id={})", workflow.getName(), workflow.getId());
        return workflow;
    }

    public Optional<Workflow> getWorkflow(String id) {
        return Optional.ofNullable(workflows.get(id));
    }

    public Collection<Workflow> getAllWorkflows() {
        return Collections.unmodifiableCollection(workflows.values());
    }

    public boolean deleteWorkflow(String id) {
        return workflows.remove(id) != null;
    }

    public Workflow updateWorkflow(String id, Workflow updated) {
        if (!workflows.containsKey(id)) {
            throw new NoSuchElementException("Workflow not found: " + id);
        }
        updated.setId(id);
        workflows.put(id, updated);
        return updated;
    }

    public String exportWorkflowYaml(String id) {
        Workflow wf = workflows.get(id);
        if (wf == null) throw new NoSuchElementException("Workflow not found: " + id);
        try {
            return loader.toYaml(wf);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export workflow as YAML", e);
        }
    }

    public Workflow importWorkflowYaml(String yaml) {
        try {
            Workflow wf = loader.fromYaml(yaml);
            return registerWorkflow(wf);
        } catch (Exception e) {
            throw new RuntimeException("Failed to import workflow from YAML: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Trade management
    // -------------------------------------------------------------------------

    public Trade createTrade(String workflowId, Map<String, Object> initialMetadata) {
        Workflow workflow = requireWorkflow(workflowId);

        Trade trade = new Trade();
        trade.setId(UUID.randomUUID().toString());
        trade.setWorkflowId(workflowId);
        trade.setMetadata(initialMetadata == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(initialMetadata));
        trade.setCreatedAt(Instant.now());
        trade.setUpdatedAt(Instant.now());
        trade.setCurrentState(workflow.getInitialState());

        trades.put(trade.getId(), trade);
        log.info("Created trade '{}' in workflow '{}', initial state='{}'",
                trade.getId(), workflowId, trade.getCurrentState());

        processAutoEvents(trade, workflow, 0);
        return trade;
    }

    public Optional<Trade> getTrade(String tradeId) {
        return Optional.ofNullable(trades.get(tradeId));
    }

    public Collection<Trade> getAllTrades() {
        return Collections.unmodifiableCollection(trades.values());
    }

    public Collection<Trade> getTradesByWorkflow(String workflowId) {
        return trades.values().stream()
                .filter(t -> workflowId.equals(t.getWorkflowId()))
                .collect(Collectors.toList());
    }

    public boolean deleteTrade(String tradeId) {
        return trades.remove(tradeId) != null;
    }

    // -------------------------------------------------------------------------
    // Event triggering
    // -------------------------------------------------------------------------

    /**
     * Triggers an EXTERNAL event on a trade. {@code params} are optional caller-supplied
     * key-values available as {@code ${key}} in declarative outcome mutations.
     */
    public Trade triggerEvent(String tradeId, String eventName, Map<String, Object> params) {
        Trade trade = requireTrade(tradeId);
        Workflow workflow = requireWorkflow(trade.getWorkflowId());

        WorkflowEvent event = findEvent(workflow, eventName, trade.getCurrentState())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Event '" + eventName + "' is not available from state '"
                        + trade.getCurrentState() + "'"));

        if (event.getTrigger() == TriggerType.AUTO) {
            throw new IllegalArgumentException(
                    "Event '" + eventName + "' is AUTO and cannot be triggered externally");
        }

        return applyEvent(trade, workflow, event, params, 0);
    }

    public List<String> getAvailableEvents(String tradeId) {
        Trade trade = requireTrade(tradeId);
        Workflow workflow = requireWorkflow(trade.getWorkflowId());

        if (workflow.getEvents() == null) return Collections.emptyList();
        return workflow.getEvents().stream()
                .filter(e -> e.getTrigger() == TriggerType.EXTERNAL
                        && e.getFromState() != null
                        && e.getFromState().equals(trade.getCurrentState()))
                .map(WorkflowEvent::getName)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Graph data for UI
    // -------------------------------------------------------------------------

    public Map<String, Object> buildGraphData(String workflowId) {
        Workflow workflow = requireWorkflow(workflowId);

        Map<String, Long> tradeCounts = trades.values().stream()
                .filter(t -> workflowId.equals(t.getWorkflowId()))
                .collect(Collectors.groupingBy(
                        t -> t.getCurrentState() != null ? t.getCurrentState() : "__unknown__",
                        Collectors.counting()));

        List<Map<String, Object>> nodes = new ArrayList<>();
        if (workflow.getStates() != null) {
            for (WorkflowState state : workflow.getStates()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", state.getName());
                node.put("label", state.getName());
                node.put("description", state.getDescription());
                node.put("autoEvents", state.getAutoEvents());
                node.put("tradeCount", tradeCounts.getOrDefault(state.getName(), 0L));
                node.put("isInitial", state.getName().equals(workflow.getInitialState()));
                nodes.add(node);
            }
        }

        // One edge per (event, outcome) pairing.
        List<Map<String, Object>> edges = new ArrayList<>();
        if (workflow.getEvents() != null) {
            for (WorkflowEvent event : workflow.getEvents()) {
                List<Outcome> outs = event.getOutcomes();
                if (outs == null || outs.isEmpty()) continue;
                boolean multi = outs.size() > 1;
                for (Outcome outcome : outs) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("id", event.getName() + "/" + outcome.getName());
                    edge.put("eventName", event.getName());
                    edge.put("outcomeName", outcome.getName());
                    edge.put("label", multi
                            ? event.getName() + " : " + outcome.getName()
                            : event.getName());
                    edge.put("from", event.getFromState());
                    edge.put("to",   outcome.getToState());
                    edge.put("trigger", event.getTrigger());
                    edge.put("type",    event.getType());
                    edge.put("when",    outcome.getWhen());
                    edge.put("description", event.getDescription());
                    edges.add(edge);
                }
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    // -------------------------------------------------------------------------
    // Internal: event execution
    // -------------------------------------------------------------------------

    private Trade applyEvent(Trade trade, Workflow workflow, WorkflowEvent event,
                             Map<String, Object> params, int depth) {
        String fromState = trade.getCurrentState();
        if (event.getOutcomes() == null || event.getOutcomes().isEmpty()) {
            throw new IllegalStateException(
                    "Event '" + event.getName() + "' has no outcomes declared");
        }

        Map<String, Object> metadata = new LinkedHashMap<>(trade.getMetadata());
        Outcome chosen;

        TransitionType type = event.getType() != null
                ? event.getType() : TransitionType.DECLARATIVE;
        switch (type) {
            case DECLARATIVE -> {
                chosen = pickDeclarativeOutcome(event, metadata);
                metadata = applyOutcomeMutations(chosen, metadata, params);
            }
            case JAVASCRIPT, PYTHON -> {
                ScriptExecutor.ScriptResult result = (type == TransitionType.JAVASCRIPT)
                        ? scriptExecutor.executeJavaScript(event.getScript(), metadata)
                        : scriptExecutor.executePython(event.getScript(), metadata);
                if (result.metadata() != null) metadata = result.metadata();
                chosen = findOutcomeByName(event, result.outcome())
                        .orElseThrow(() -> new IllegalStateException(
                                "Script returned unknown outcome '" + result.outcome()
                                + "' for event '" + event.getName() + "'"));
            }
            default -> throw new IllegalStateException("Unsupported event type: " + type);
        }

        String newState = chosen.getToState();
        if (newState == null || newState.isBlank()) {
            throw new IllegalStateException(
                    "Outcome '" + chosen.getName() + "' of event '" + event.getName()
                    + "' has no toState");
        }

        trade.setMetadata(metadata);
        trade.setCurrentState(newState);
        trade.setUpdatedAt(Instant.now());

        TradeHistoryEntry entry = new TradeHistoryEntry();
        entry.setTimestamp(Instant.now());
        entry.setEventName(event.getName());
        entry.setOutcomeName(chosen.getName());
        entry.setFromState(fromState);
        entry.setToState(newState);
        entry.setMetadataSnapshot(new LinkedHashMap<>(metadata));
        trade.getHistory().add(entry);

        log.info("Trade '{}': event '{}' [{}] -> {} -> {}",
                trade.getId(), event.getName(), chosen.getName(), fromState, newState);

        processAutoEvents(trade, workflow, depth + 1);
        return trade;
    }

    private void processAutoEvents(Trade trade, Workflow workflow, int depth) {
        if (depth >= MAX_AUTO_EVENT_DEPTH) {
            log.warn("Trade '{}': auto-event depth limit reached – possible cycle", trade.getId());
            return;
        }
        WorkflowState currentState = findStateByName(workflow, trade.getCurrentState());
        if (currentState == null || currentState.getAutoEvents() == null
                || currentState.getAutoEvents().isEmpty()) {
            return;
        }
        for (String autoEventName : currentState.getAutoEvents()) {
            Optional<WorkflowEvent> autoEvent =
                    findEvent(workflow, autoEventName, trade.getCurrentState());
            if (autoEvent.isPresent() && autoEvent.get().getTrigger() == TriggerType.AUTO) {
                log.info("Trade '{}': auto-triggering '{}'", trade.getId(), autoEventName);
                applyEvent(trade, workflow, autoEvent.get(), null, depth + 1);
                return; // Re-evaluate from the new state
            }
        }
    }

    // -------------------------------------------------------------------------
    // Outcome resolution
    // -------------------------------------------------------------------------

    /**
     * Walk outcomes in declaration order; pick the first whose {@code when}
     * predicate evaluates to true (a missing predicate always matches).
     */
    private Outcome pickDeclarativeOutcome(WorkflowEvent event, Map<String, Object> metadata) {
        for (Outcome o : event.getOutcomes()) {
            if (o.getWhen() == null || o.getWhen().isBlank()
                    || evaluatePredicate(o.getWhen(), metadata)) {
                return o;
            }
        }
        throw new IllegalStateException(
                "No outcome matched for declarative event '" + event.getName()
                + "'. Provide a default outcome with no 'when'.");
    }

    private Optional<Outcome> findOutcomeByName(WorkflowEvent event, String name) {
        if (name == null) return Optional.empty();
        return event.getOutcomes().stream()
                .filter(o -> name.equals(o.getName()))
                .findFirst();
    }

    /**
     * Tiny expression evaluator for declarative {@code when} predicates.
     * Format: {@code <key> <op> <literal>} where op ∈ {==, !=, &gt;, &gt;=, &lt;, &lt;=}.
     * Numeric comparison is used when both sides parse as numbers.
     */
    private boolean evaluatePredicate(String expr, Map<String, Object> metadata) {
        String[] ops = {"==", "!=", ">=", "<=", ">", "<"};
        for (String op : ops) {
            int idx = findOperator(expr, op);
            if (idx >= 0) {
                String lhs = expr.substring(0, idx).trim();
                String rhs = expr.substring(idx + op.length()).trim();
                Object lhsValue = metadata.get(lhs);
                Object rhsValue = parseLiteral(rhs);
                return compare(lhsValue, op, rhsValue);
            }
        }
        throw new IllegalArgumentException("Invalid 'when' expression: '" + expr + "'");
    }

    /** Find an operator that isn't a substring of another (e.g. '>' inside '>='). */
    private int findOperator(String expr, String op) {
        int idx = expr.indexOf(op);
        if (idx < 0) return -1;
        // Reject '>' if it's actually '>='/'<='/etc that we'll match in a later iteration.
        if ((op.equals(">") || op.equals("<")) && idx + 1 < expr.length()
                && expr.charAt(idx + 1) == '=') {
            return -1;
        }
        return idx;
    }

    private Object parseLiteral(String s) {
        if (s.length() >= 2
                && ((s.startsWith("'") && s.endsWith("'"))
                 || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        if ("true".equalsIgnoreCase(s))  return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        if ("null".equalsIgnoreCase(s))  return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        return s;
    }

    private boolean compare(Object lhs, String op, Object rhs) {
        // Equality / inequality: tolerate type mismatches via toString().
        if ("==".equals(op)) return Objects.equals(stringOf(lhs), stringOf(rhs));
        if ("!=".equals(op)) return !Objects.equals(stringOf(lhs), stringOf(rhs));
        Double l = toDouble(lhs), r = toDouble(rhs);
        if (l == null || r == null) {
            throw new IllegalArgumentException(
                    "Cannot compare non-numeric values with operator '" + op + "'");
        }
        return switch (op) {
            case ">"  -> l >  r;
            case ">=" -> l >= r;
            case "<"  -> l <  r;
            case "<=" -> l <= r;
            default   -> throw new IllegalStateException("Unknown op " + op);
        };
    }

    private String stringOf(Object o) { return o == null ? null : String.valueOf(o); }

    private Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); }
        catch (NumberFormatException e) { return null; }
    }

    // -------------------------------------------------------------------------
    // Declarative metadata mutation
    // -------------------------------------------------------------------------

    private Map<String, Object> applyOutcomeMutations(Outcome outcome,
                                                      Map<String, Object> metadata,
                                                      Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        if (outcome.getSet() != null) {
            for (Map.Entry<String, String> entry : outcome.getSet().entrySet()) {
                result.put(entry.getKey(), resolveExpression(entry.getValue(), metadata, params));
            }
        }
        if (outcome.getUnset() != null) {
            outcome.getUnset().forEach(result::remove);
        }
        return result;
    }

    private String resolveExpression(String value, Map<String, Object> metadata,
                                     Map<String, Object> params) {
        if (value == null) return null;
        if ("${now}".equals(value)) return Instant.now().toString();
        if (value.startsWith("${") && value.endsWith("}")) {
            String key = value.substring(2, value.length() - 1);
            if (params != null && params.containsKey(key)) return String.valueOf(params.get(key));
            if (metadata.containsKey(key)) return String.valueOf(metadata.get(key));
        }
        return value;
    }

    // -------------------------------------------------------------------------
    // Lookups
    // -------------------------------------------------------------------------

    private Optional<WorkflowEvent> findEvent(Workflow workflow, String name, String fromState) {
        if (workflow.getEvents() == null) return Optional.empty();
        return workflow.getEvents().stream()
                .filter(e -> name.equals(e.getName()) && fromState != null
                        && fromState.equals(e.getFromState()))
                .findFirst();
    }

    private WorkflowState findStateByName(Workflow workflow, String name) {
        if (workflow.getStates() == null || name == null) return null;
        return workflow.getStates().stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst().orElse(null);
    }

    private Workflow requireWorkflow(String id) {
        Workflow wf = workflows.get(id);
        if (wf == null) throw new NoSuchElementException("Workflow not found: " + id);
        return wf;
    }

    private Trade requireTrade(String id) {
        Trade t = trades.get(id);
        if (t == null) throw new NoSuchElementException("Trade not found: " + id);
        return t;
    }

    // -------------------------------------------------------------------------
    // Built-in workflow loading
    // -------------------------------------------------------------------------

    private void loadBuiltinWorkflows() {
        loadWorkflowFromClasspath("/workflows/trade-settlement.yaml");
        loadWorkflowFromClasspath("/workflows/fx-spot.yaml");
    }

    private void loadWorkflowFromClasspath(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                log.debug("Built-in workflow not found at classpath:{}", path);
                return;
            }
            String yaml = new String(is.readAllBytes());
            Workflow wf = loader.fromYaml(yaml);
            registerWorkflow(wf);
            log.info("Loaded built-in workflow '{}' from {}", wf.getName(), path);
        } catch (Exception e) {
            log.warn("Failed to load built-in workflow from {}: {}", path, e.getMessage());
        }
    }
}
