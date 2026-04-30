package com.tradeworkflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes event scripts written in JavaScript (via Rhino) or Python (via Jython).
 *
 * <p>Scripts must define a function {@code execute(metadata)} that returns
 * {@code { outcome: "<name>", metadata: { ... } }}. The {@code outcome} string must
 * match one of the event's declared outcomes; the returned {@code metadata} (if
 * present) replaces the trade's metadata.
 *
 * <pre>JavaScript:
 * function execute(metadata) {
 *   if (metadata.amount &gt; 0) {
 *     metadata.validatedAt = new Date().toISOString();
 *     return { outcome: 'success', metadata: metadata };
 *   }
 *   return { outcome: 'failure', metadata: metadata };
 * }</pre>
 *
 * <pre>Python:
 * def execute(metadata):
 *     if metadata.get('amount', 0) &gt; 0:
 *         metadata['validatedAt'] = datetime.datetime.utcnow().isoformat()
 *         return {'outcome': 'success', 'metadata': metadata}
 *     return {'outcome': 'failure', 'metadata': metadata}</pre>
 */
public class ScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScriptExecutor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Result of executing an event script. */
    public record ScriptResult(String outcome, Map<String, Object> metadata) {}

    public ScriptResult executeJavaScript(String script, Map<String, Object> metadata) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("JavaScript event has no script body");
        }
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            Scriptable scope = cx.initStandardObjects();

            String metadataJson = objectMapper.writeValueAsString(metadata);

            String fullScript =
                    "var metadata = JSON.parse('" + escapeForJsSingleQuoted(metadataJson) + "');\n"
                    + script + "\n"
                    + "var __result__ = execute(metadata);\n"
                    + "JSON.stringify(__result__);";

            Object rawResult = cx.evaluateString(scope, fullScript, "<script>", 1, null);

            String resultJson = (rawResult instanceof String)
                    ? (String) rawResult
                    : objectMapper.writeValueAsString(rawResult);

            return parseScriptResult(resultJson, metadata);
        } catch (Exception e) {
            log.error("JavaScript execution failed", e);
            throw new RuntimeException("JavaScript execution failed: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    public ScriptResult executePython(String script, Map<String, Object> metadata) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("Python event has no script body");
        }
        try {
            Class<?> interpreterClass = Class.forName("org.python.util.PythonInterpreter");
            Object interpreter = interpreterClass.getDeclaredConstructor().newInstance();

            String metadataJson = objectMapper.writeValueAsString(metadata);
            interpreterClass.getMethod("set", String.class, Object.class)
                    .invoke(interpreter, "_metadata_json_input_", metadataJson);

            String fullScript =
                    "import json\n"
                    + "metadata = json.loads(_metadata_json_input_)\n"
                    + script + "\n"
                    + "_result_ = json.dumps(execute(metadata))\n";

            interpreterClass.getMethod("exec", String.class).invoke(interpreter, fullScript);

            String resultJson = (String) interpreterClass
                    .getMethod("get", String.class, Class.class)
                    .invoke(interpreter, "_result_", String.class);

            interpreterClass.getMethod("close").invoke(interpreter);

            return parseScriptResult(resultJson, metadata);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Python scripting requires Jython on the classpath", e);
        } catch (Exception e) {
            log.error("Python execution failed", e);
            throw new RuntimeException("Python execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * Accept either:
     *   - a JSON object {@code { "outcome": "name", "metadata": { ... } }}
     *   - a bare JSON string {@code "name"} (metadata unchanged)
     */
    @SuppressWarnings("unchecked")
    private ScriptResult parseScriptResult(String resultJson, Map<String, Object> originalMetadata)
            throws Exception {
        Object parsed = objectMapper.readValue(resultJson, Object.class);
        if (parsed instanceof String s) {
            return new ScriptResult(s, originalMetadata);
        }
        if (parsed instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((k, v) -> typed.put(String.valueOf(k), v));
            Object outcome  = typed.get("outcome");
            Object metaObj  = typed.get("metadata");
            if (outcome == null) {
                throw new IllegalStateException(
                        "Script result missing required 'outcome' key. Got: " + resultJson);
            }
            Map<String, Object> meta = (metaObj instanceof Map<?, ?>)
                    ? (Map<String, Object>) metaObj : originalMetadata;
            return new ScriptResult(String.valueOf(outcome), meta);
        }
        throw new IllegalStateException("Unexpected script return shape: " + resultJson);
    }

    private String escapeForJsSingleQuoted(String json) {
        return json.replace("\\", "\\\\").replace("'", "\\'");
    }
}
