package com.compute.temporal;

import com.compute.model.LaneRef;
import com.compute.temporal.activity.DefinitionActivities;
import com.compute.temporal.activity.ImageActivities;
import com.compute.temporal.activity.OcpActivities;
import com.compute.temporal.activity.OrchestrationActivities;
import com.compute.temporal.workflow.impl.JobWorkflowImpl;
import com.compute.temporal.workflow.impl.SchedulerWorkflowImpl;
import com.compute.temporal.workflow.impl.ServiceWorkflowImpl;
import com.compute.temporal.workflow.impl.TaskWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a {@link WorkerFactory} per Temporal namespace. The system namespace runs
 * the {@code SchedulerWorkflow}; each {@code (group, project, lane)} namespace runs
 * service / job / task-parent workflows for that triple. Subtask workers live in
 * separate processes (the ephemeral OCP Job) and are NOT managed here.
 */
public final class ControlPlaneWorkers implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneWorkers.class);

    private final WorkflowServiceStubs stubs;
    private final NamespaceResolver namespaces;
    private final TemporalConfig config;
    private final Activities activities;
    private final Map<String, WorkerFactory> factories = new LinkedHashMap<>();
    private final Set<String> wiredNamespaces = new LinkedHashSet<>();

    public ControlPlaneWorkers(WorkflowServiceStubs stubs, NamespaceResolver namespaces,
                               TemporalConfig config, Activities activities) {
        this.stubs = stubs;
        this.namespaces = namespaces;
        this.config = config;
        this.activities = activities;
    }

    /** Start the system worker that runs the SchedulerWorkflow. */
    public WorkflowClient startSystemWorker() {
        String ns = namespaces.ensure(config.systemNamespace());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(ns).build());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker w = factory.newWorker(TaskQueues.forSystem());
        w.registerWorkflowImplementationTypes(SchedulerWorkflowImpl.class);
        w.registerActivitiesImplementations(activities.definitions(), activities.orchestration());
        factory.start();
        factories.put(ns, factory);
        log.info("system worker started in namespace {} on queue {}", ns, TaskQueues.forSystem());
        return client;
    }

    /** Ensure a worker is running for the given (group, project, lane) namespace. */
    public WorkflowClient ensureWorker(LaneRef ref) {
        String ns = namespaces.ensure(ref);
        if (wiredNamespaces.contains(ns)) {
            return WorkflowClient.newInstance(stubs,
                    WorkflowClientOptions.newBuilder().setNamespace(ns).build());
        }
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(ns).build());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker w = factory.newWorker(TaskQueues.forNamespace(ns));
        w.registerWorkflowImplementationTypes(
                ServiceWorkflowImpl.class,
                JobWorkflowImpl.class,
                TaskWorkflowImpl.class);
        // DefinitionActivities is registered here so ProjectSchedulerWorkflow (which runs in
        // this namespace) can call loadForProject() as a local activity.
        w.registerActivitiesImplementations(
                activities.images(), activities.ocp(), activities.orchestration(),
                activities.definitions());
        factory.start();
        factories.put(ns, factory);
        wiredNamespaces.add(ns);
        log.info("namespace worker started in {} on queue {}", ns, TaskQueues.forNamespace(ns));
        return client;
    }

    @Override
    public void close() {
        factories.values().forEach(WorkerFactory::shutdown);
    }

    /** Bag of activity implementations. Keeps wiring concise. */
    public record Activities(
            DefinitionActivities definitions,
            ImageActivities images,
            OcpActivities ocp,
            OrchestrationActivities orchestration
    ) {}
}
