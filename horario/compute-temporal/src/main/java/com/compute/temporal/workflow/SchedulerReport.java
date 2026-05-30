package com.compute.temporal.workflow;

import java.util.List;

public record SchedulerReport(
        List<String> servicesStarted,
        List<String> servicesStopped,
        List<String> servicesRedeployed,
        List<String> jobsScheduled,
        List<String> warnings
) {}
