package io.acme.orchestrator.k8s;

/**
 * Central registry of label keys, annotation keys, and path conventions.
 * Keeping these in one place means the Informers can filter reliably and
 * the dependency engine can round-trip from a fabric8 {@code Job} back to
 * the logical job name that produced it.
 */
public final class Labels {
    private Labels() {}

    public static final String KEY_MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String VAL_MANAGED_BY = "acme-orchestrator";

    public static final String KEY_KIND       = "orchestrator.acme.io/kind";
    public static final String VAL_KIND_JOB   = "job";
    public static final String VAL_KIND_SVC   = "service";

    /** Logical (user-visible) name of the job or service. */
    public static final String KEY_LOGICAL_NAME = "orchestrator.acme.io/name";

    /** Unique identifier of a specific firing of a job. */
    public static final String KEY_RUN_ID       = "orchestrator.acme.io/run-id";

    /** Wall-clock time this run was scheduled for (ISO-8601). */
    public static final String ANN_FIRE_TIME    = "orchestrator.acme.io/fire-time";

    /** Comma-separated list of dependency names, for traceability. */
    public static final String ANN_DEPENDENCIES = "orchestrator.acme.io/dependencies";

    /** Mount path inside the main container where JARs are assembled. */
    public static final String CLASSPATH_MOUNT  = "/opt/app/classpath";

    /** Name of the shared emptyDir volume. */
    public static final String CLASSPATH_VOLUME = "classpath";

    // ConfigMap conventions --------------------------------------------------

    /** Label value used on CMs that store a JobDefinition. */
    public static final String VAL_KIND_JOB_DEF     = "job-definition";
    /** Label value used on CMs that store a ServiceDefinition. */
    public static final String VAL_KIND_SERVICE_DEF = "service-definition";
    /** Label value used on CMs that persist an in-flight PENDING_DEPS run. */
    public static final String VAL_KIND_PENDING_RUN = "pending-run";

    /** Key inside all our CMs that holds the JSON payload. */
    public static final String CM_DATA_KEY = "spec.json";

    public static String jobDefCmName(String logicalName)     { return "jobdef-" + logicalName; }
    public static String serviceDefCmName(String logicalName) { return "svcdef-" + logicalName; }
    public static String pendingRunCmName(String logicalName, String runId) {
        return "pending-" + logicalName + "-" + runId;
    }
}
