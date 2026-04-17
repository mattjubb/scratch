package io.acme.example.job;

import io.acme.example.shared.Greeter;

/**
 * Toy run-to-completion workload.
 * <p>
 * Reads an input env var, "does work" (a short sleep), writes output to stdout,
 * and exits with status 0 on success. Exit status drives the Kubernetes Job
 * condition that the orchestrator's {@code JobInformer} listens for.
 */
public final class ReportJob {

    public static void main(String[] args) throws InterruptedException {
        String dataset = System.getenv().getOrDefault("DATASET", "treasury-eod");
        System.out.println("ReportJob starting for dataset=" + dataset);
        System.out.println(Greeter.greet("ReportJob"));

        // Simulate work; real jobs might hit a DB, an S3 bucket, etc.
        Thread.sleep(3_000);

        // Emit a synthetic "row count" so downstream jobs could, if they
        // wanted, pull it from the Pod logs or a sidecar-exported metric.
        System.out.println("ReportJob wrote 42 rows for " + dataset);
        System.out.println("ReportJob done");
    }
}
