package com.compute.yaml;

import com.compute.model.JobDefinition;
import com.compute.model.Lane;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionLoaderSchedulerTest {

    @Test
    void parsesSchedulerJob(@TempDir Path root) throws Exception {
        Path f = root.resolve("jobs/rates/eod/scheduler.yaml");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                group: rates
                project: eod
                scheduler: true
                schedule: "0 0 * * *"
                lookaheadDays: 3
                """);

        DefinitionLoader.Result r = new DefinitionLoader(root, Lane.DEV).load();
        assertThat(r.jobs()).hasSize(1);
        JobDefinition j = r.jobs().get(0);
        assertThat(j.scheduler()).isTrue();
        assertThat(j.lookaheadDays()).isEqualTo(3);
        assertThat(j.mainClass()).isEmpty();
        assertThat(j.id().path()).isEqualTo("/rates/eod/scheduler");
        assertThat(j.schedule()).isEqualTo("0 0 * * *");
    }

    @Test
    void schedulerJobDefaultLookahead(@TempDir Path root) throws Exception {
        Path f = root.resolve("jobs/core/entitlements/scheduler.yaml");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                group: core
                project: entitlements
                scheduler: true
                schedule: "0 0 * * *"
                """);

        DefinitionLoader.Result r = new DefinitionLoader(root, Lane.DEV).load();
        assertThat(r.jobs().get(0).lookaheadDays()).isEqualTo(2); // default
    }

    @Test
    void loadForProjectFiltersCorrectly(@TempDir Path root) throws Exception {
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

        Path jobA = root.resolve("jobs/rates/swaps/curve.yaml");
        Files.createDirectories(jobA.getParent());
        Files.writeString(jobA, """
                group: rates
                project: swaps
                mainClass: com.example.CurveJob
                """);

        DefinitionLoader loader = new DefinitionLoader(root, Lane.DEV);
        DefinitionLoader.Result r = loader.loadForProject("rates", "swaps");

        assertThat(r.services()).hasSize(1);
        assertThat(r.services().get(0).group()).isEqualTo("rates");
        assertThat(r.jobs()).hasSize(1);
        assertThat(r.jobs().get(0).group()).isEqualTo("rates");
    }

    @Test
    void loadForProjectReturnsEmptyWhenNoMatch(@TempDir Path root) throws Exception {
        Path svc = root.resolve("services/rates/swaps/pricer.yaml");
        Files.createDirectories(svc.getParent());
        Files.writeString(svc, """
                group: rates
                project: swaps
                mainClass: com.example.Pricer
                """);

        DefinitionLoader.Result r = new DefinitionLoader(root, Lane.DEV)
                .loadForProject("fx", "options");
        assertThat(r.services()).isEmpty();
        assertThat(r.jobs()).isEmpty();
    }

    @Test
    void missingRequiredFieldThrows(@TempDir Path root) throws Exception {
        Path f = root.resolve("jobs/rates/eod/bad.yaml");
        Files.createDirectories(f.getParent());
        // mainClass missing and scheduler=false (default)
        Files.writeString(f, """
                group: rates
                project: eod
                """);

        assertThatThrownBy(() -> new DefinitionLoader(root, Lane.DEV).load())
                .isInstanceOf(DefinitionParseException.class);
    }

    @Test
    void mismatchedProjectThrows(@TempDir Path root) throws Exception {
        Path f = root.resolve("jobs/rates/eod/curve.yaml");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                group: rates
                project: WRONG_PROJECT
                mainClass: com.example.Job
                """);

        assertThatThrownBy(() -> new DefinitionLoader(root, Lane.DEV).load())
                .isInstanceOf(DefinitionParseException.class)
                .hasMessageContaining("doesn't match path segment");
    }

    @Test
    void multipleJobsAndServicesSorted(@TempDir Path root) throws Exception {
        for (String name : new String[]{"b-job.yaml", "a-job.yaml"}) {
            Path f = root.resolve("jobs/rates/eod/" + name);
            Files.createDirectories(f.getParent());
            Files.writeString(f, """
                    group: rates
                    project: eod
                    mainClass: com.example.Job
                    """);
        }

        DefinitionLoader.Result r = new DefinitionLoader(root, Lane.DEV).load();
        assertThat(r.jobs()).hasSize(2);
        // walk() sorts alphabetically
        assertThat(r.jobs().get(0).id().name()).isEqualTo("a-job");
        assertThat(r.jobs().get(1).id().name()).isEqualTo("b-job");
    }

    @Test
    void laneIsInjectedFromLoader(@TempDir Path root) throws Exception {
        Path f = root.resolve("jobs/fx/eod/snap.yaml");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                group: fx
                project: eod
                mainClass: com.example.Snap
                """);

        DefinitionLoader.Result r = new DefinitionLoader(root, Lane.UAT).load();
        assertThat(r.jobs().get(0).lane()).isEqualTo(Lane.UAT);
    }
}
