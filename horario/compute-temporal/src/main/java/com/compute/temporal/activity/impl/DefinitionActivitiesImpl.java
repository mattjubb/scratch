package com.compute.temporal.activity.impl;

import com.compute.temporal.activity.DefinitionActivities;
import com.compute.yaml.DefinitionLoader;

public final class DefinitionActivitiesImpl implements DefinitionActivities {

    private final DefinitionLoader loader;

    public DefinitionActivitiesImpl(DefinitionLoader loader) {
        this.loader = loader;
    }

    @Override
    public Loaded loadAll() {
        DefinitionLoader.Result r = loader.load();
        return new Loaded(r.services(), r.jobs());
    }

    @Override
    public Loaded loadForProject(String group, String project) {
        DefinitionLoader.Result r = loader.loadForProject(group, project);
        return new Loaded(r.services(), r.jobs());
    }
}
