package io.acme.example.shared;

/**
 * Deliberately trivial. Its purpose is to prove, at runtime, that the shared
 * layer was staged correctly onto the classpath by the orchestrator's
 * init-container chain: both the example job and the example service import
 * and call this class, and will fail at classload time if the layer is missing.
 */
public final class Greeter {

    private Greeter() {}

    public static String greet(String who) {
        return "hello, " + who + " — signed, shared-lib";
    }
}
