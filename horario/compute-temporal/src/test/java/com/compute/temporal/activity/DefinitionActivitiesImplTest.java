package com.compute.temporal.activity;

import com.compute.model.Lane;
import com.compute.temporal.activity.impl.DefinitionActivitiesImpl;
import com.compute.yaml.DefinitionLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionActivitiesImplTest {

    @Test
    void loadAllDelegatesToLoader(@TempDir Path root) throws Exception {
        Path svc = root.resolve("services/rates/swaps/pricer.yaml");
        Files.createDirectories(svc.getParent());
        Files.writeString(svc, """
                group: rates
                project: swaps
                mainClass: com.example.Pricer
                """);
        Path job = root.resolve("jobs/rates/swaps/daily.yaml");
        Files.createDirectories(job.getParent());
        Files.writeString(job, """
                group: rates
                project: swaps
                mainClass: com.example.Daily
                """);

        DefinitionLoader loader = new DefinitionLoader(root, Lane.DEV);
        DefinitionActivitiesImpl impl = new DefinitionActivitiesImpl(loader);
        DefinitionActivities.Loaded loaded = impl.loadAll();

        assertThat(loaded.services()).hasSize(1);
        assertThat(loaded.jobs()).hasSize(1);
        assertThat(loaded.services().get(0).group()).isEqualTo("rates");
        assertThat(loaded.jobs().get(0).mainClass()).isEqualTo("com.example.Daily");
    }

    @Test
    void loadForProjectFilters(@TempDir Path root) throws Exception {
        Path svcA = root.resolve("services/rates/swaps/pricer.yaml");
        Files.createDirectories(svcA.getParent());
        Files.writeString(svcA, """
                group: rates
                project: swaps
                mainClass: com.example.Pricer
                """);
        Path svcB = root.resolve("services/fx/options/vol.yaml");
        Files.createDirectories(svcB.getParent());
        Files.writeString(svcB, """
                group: fx
                project: options
                mainClass: com.example.Vol
                """);

        DefinitionLoader loader = new DefinitionLoader(root, Lane.DEV);
        DefinitionActivitiesImpl impl = new DefinitionActivitiesImpl(loader);
        DefinitionActivities.Loaded loaded = impl.loadForProject("rates", "swaps");

        assertThat(loaded.services()).hasSize(1);
        assertThat(loaded.services().get(0).project()).isEqualTo("swaps");
        assertThat(loaded.jobs()).isEmpty();
    }

    @Test
    void loadAllReturnsEmptyWhenNoDefinitions(@TempDir Path root) {
        DefinitionLoader loader = new DefinitionLoader(root, Lane.DEV);
        DefinitionActivitiesImpl impl = new DefinitionActivitiesImpl(loader);
        DefinitionActivities.Loaded loaded = impl.loadAll();

        assertThat(loaded.services()).isEmpty();
        assertThat(loaded.jobs()).isEmpty();
    }
}
