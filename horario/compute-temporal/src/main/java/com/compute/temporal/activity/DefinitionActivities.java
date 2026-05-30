package com.compute.temporal.activity;

import com.compute.model.JobDefinition;
import com.compute.model.ServiceDefinition;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

/** YAML-tree access for the scheduler. */
@ActivityInterface
public interface DefinitionActivities {

    /** Load all services and jobs. */
    @ActivityMethod
    Loaded loadAll();

    /**
     * Load services and jobs belonging to a specific project.
     * Called from within a {@code JobWorkflow} acting as a project scheduler.
     */
    @ActivityMethod
    Loaded loadForProject(String group, String project);

    record Loaded(List<ServiceDefinition> services, List<JobDefinition> jobs) {}
}
