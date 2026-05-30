package com.example.randomnumbers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Standalone client that submits a batch of random-number subtasks to the Vasara
 * Compute control plane.
 *
 * <pre>
 * Usage:
 *   java -jar random-numbers-submit.jar [base-url] [lane] [subtask-count] [numbers-per-subtask] [parallelism]
 *
 * Defaults:
 *   base-url            http://localhost:8080
 *   lane                dev
 *   subtask-count       1000
 *   numbers-per-subtask 10000
 *   parallelism         50        (worker pods; each handles ~20 subtasks)
 *
 * Example — 1 000 subtasks, 10 000 doubles each, 50 workers:
 *   java -jar random-numbers-submit.jar
 *
 * Example — 500 subtasks, 1 000 000 doubles each, 100 workers (heavy):
 *   java -jar random-numbers-submit.jar http://localhost:8080 dev 500 1000000 100
 * </pre>
 *
 * <p>The submitted subtasks have the shape:
 * <pre>
 * { "subtaskId": "sub-0000", "kind": "random-numbers", "args": { "count": 10000 } }
 * </pre>
 *
 * <p>The control plane starts a {@code TaskWorkflow}, provisions the requested number
 * of worker pods via an ephemeral OCP Job, and executes every subtask in parallel.
 * Progress is visible on the Tasks tab of the Vasara UI.
 */
public final class SubmitTask {

    public static void main(String[] args) throws Exception {
        String baseUrl     = argOr(args, 0, "http://localhost:8080");
        String lane        = argOr(args, 1, "dev");
        int subtaskCount   = Integer.parseInt(argOr(args, 2, "1000"));
        int numbersEach    = Integer.parseInt(argOr(args, 3, "10000"));
        int parallelism    = Integer.parseInt(argOr(args, 4, "50"));

        // Seed is optional; omit it for non-deterministic output (realistic benchmark).
        // Pass an explicit seed (e.g. "args": {"count": 10000, "seed": 42}) for
        // reproducible results.

        System.out.printf("Submitting %d subtasks%n", subtaskCount);
        System.out.printf("  numbers per subtask : %,d%n", numbersEach);
        System.out.printf("  total random numbers: %,d%n", (long) subtaskCount * numbersEach);
        System.out.printf("  parallelism (pods)  : %d%n", parallelism);
        System.out.printf("  lane                : %s%n", lane);
        System.out.printf("  control plane       : %s%n", baseUrl);
        System.out.println();

        String body = buildRequestBody(lane, subtaskCount, numbersEach, parallelism);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        System.out.println("POSTing to " + baseUrl + "/api/tasks ...");
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("HTTP " + resp.statusCode());
        System.out.println(resp.body());
        System.out.println();

        if (resp.statusCode() == 200 || resp.statusCode() == 201) {
            System.out.println("Task submitted successfully.");
            System.out.println("Open " + baseUrl + " and click the Tasks tab to watch progress.");
        } else {
            System.err.println("Submission failed — check control-plane logs.");
            System.exit(1);
        }
    }

    // ── JSON building (no external dependencies needed) ─────────────────────

    static String buildRequestBody(String lane, int subtaskCount, int numbersEach, int parallelism) {
        String subtasks = IntStream.range(0, subtaskCount)
                .mapToObj(i -> String.format(
                        "{\"subtaskId\":\"sub-%04d\",\"kind\":\"random-numbers\","
                                + "\"args\":{\"count\":%d}}",
                        i, numbersEach))
                .collect(Collectors.joining(",\n    ", "[\n    ", "\n  ]"));

        return String.format("""
                {
                  "group": "example",
                  "project": "random-numbers",
                  "lane": "%s",
                  "version": "dev",
                  "parallelism": %d,
                  "subtasks": %s
                }""", lane, parallelism, subtasks);
    }

    private static String argOr(String[] args, int idx, String def) {
        return idx < args.length ? args[idx] : def;
    }
}
