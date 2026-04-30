package com.tradeworkflow.model;

/**
 * How an event resolves which outcome (and therefore target state) to take.
 *
 * <ul>
 *   <li>{@link #DECLARATIVE} – outcomes are picked by evaluating their
 *       {@code when} predicates in order; the first matching outcome wins.
 *       The chosen outcome's {@code set}/{@code unset} are applied to metadata.</li>
 *   <li>{@link #JAVASCRIPT} – the event's {@code script} is executed via Rhino.
 *       The script must return {@code { outcome: "name", metadata: { ... } }}.</li>
 *   <li>{@link #PYTHON} – same contract as JavaScript, executed via Jython.</li>
 * </ul>
 */
public enum TransitionType {
    DECLARATIVE,
    JAVASCRIPT,
    PYTHON
}
