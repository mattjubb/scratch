package com.compute.cp;

import com.compute.model.JobDefinition;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.compute.model.ServiceDefinition;
import com.compute.yaml.DefinitionLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks the definitions tree once per lane to discover the set of
 * {@code (group, project, lane)} triples — used at startup to pre-register Temporal
 * namespaces / workers.
 */
public final class LaneCatalog {

    private final Path root;

    public LaneCatalog(Path root) {
        this.root = root;
    }

    public List<LaneRef> discover() {
        Set<LaneRef> set = new HashSet<>();
        for (Lane lane : Lane.values()) {
            DefinitionLoader.Result r = new DefinitionLoader(root, lane).load();
            for (ServiceDefinition s : r.services()) set.add(s.laneRef());
            for (JobDefinition j : r.jobs()) set.add(j.laneRef());
        }
        return new ArrayList<>(set);
    }
}
