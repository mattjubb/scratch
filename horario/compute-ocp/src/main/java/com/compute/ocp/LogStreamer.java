package com.compute.ocp;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Streams pod logs from OCP into a line-oriented consumer. */
public final class LogStreamer {

    private static final Logger log = LoggerFactory.getLogger(LogStreamer.class);

    private final OcpClientHolder ocp;

    public LogStreamer(OcpClientHolder ocp) {
        this.ocp = ocp;
    }

    /**
     * Stream logs from the first pod matching the given labels until {@code stop.get()}
     * returns true. Emits one line per consumer call. Returns the {@link LogWatch}
     * (may be null in dry-run).
     */
    public LogWatch follow(Map<String, String> labels, Consumer<String> onLine, java.util.function.BooleanSupplier stop) {
        if (ocp.isDryRun()) {
            new Thread(() -> {
                int n = 0;
                while (!stop.getAsBoolean()) {
                    onLine.accept("[dry-run] log line " + (++n));
                    try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }, "dryrun-logstream").start();
            return null;
        }

        List<Pod> pods = ocp.client().pods().inNamespace(ocp.namespace())
                .withLabels(labels).list().getItems();
        if (pods.isEmpty()) {
            log.warn("no pods matched labels {} for log stream", labels);
            return null;
        }
        Pod pod = pods.get(0);
        LogWatch watch = ocp.client().pods().inNamespace(ocp.namespace())
                .withName(pod.getMetadata().getName())
                .tailingLines(200)
                .watchLog();

        Thread t = new Thread(() -> drain(watch.getOutput(), onLine, stop), "logstream-" + pod.getMetadata().getName());
        t.setDaemon(true);
        t.start();
        return watch;
    }

    private static void drain(InputStream in, Consumer<String> onLine, java.util.function.BooleanSupplier stop) {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (!stop.getAsBoolean() && (line = reader.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (IOException e) {
            log.debug("log stream ended: {}", e.getMessage());
        }
    }
}
