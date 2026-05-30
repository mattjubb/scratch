package com.compute.yaml;

import com.compute.model.JobDefinition;
import com.compute.model.Lane;
import com.compute.model.ServiceDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionLoaderTest {

    @Test
    void derivesIdsAndParsesService(@TempDir Path root) throws Exception {
        Path svc = root.resolve("services/rates/swaps/pricer.yaml");
        Files.createDirectories(svc.getParent());
        Files.writeString(svc, """
                group: rates
                project: swaps
                mainClass: com.example.PricerApp
                version: dev
                replicas: 2
                resources: { cpu: "1", memory: "2Gi" }
                env:
                  - { name: MODE, value: live }
                ports:
                  - { name: rest, port: 8080 }
                args: [ "--server.port=8080" ]
                """);

        DefinitionLoader.Result r = new DefinitionLoader(root, Lane.DEV).load();
        assertThat(r.services()).hasSize(1);
        ServiceDefinition s = r.services().get(0);
        assertThat(s.id().path()).isEqualTo("/rates/swaps/pricer");
        assertThat(s.group()).isEqualTo("rates");
        assertThat(s.project()).isEqualTo("swaps");
        assertThat(s.replicas()).isEqualTo(2);
        assertThat(s.env()).containsExactly(new com.compute.model.EnvVar("MODE", "live"));
        assertThat(s.ports().get(0).port()).isEqualTo(8080);
        assertThat(s.args()).containsExactly("--server.port=8080");
    }

    @Test
    void parsesJobWithDeps(@TempDir Path root) throws Exception {
        Path job = root.resolve("jobs/rates/eod/curve-build.yaml");
        Files.createDirectories(job.getParent());
        Files.writeString(job, """
                group: rates
                project: eod
                mainClass: com.example.CurveBuildJob
                schedule: "0 17 * * *"
                deps: [ "/rates/eod/market-data" ]
                defaults:
                  args:
                    curve_set: all
                    valuation_ccy: USD
                """);

        DefinitionLoader.Result r = new DefinitionLoader(root, Lane.DEV).load();
        assertThat(r.jobs()).hasSize(1);
        JobDefinition j = r.jobs().get(0);
        assertThat(j.id().path()).isEqualTo("/rates/eod/curve-build");
        assertThat(j.schedule()).isEqualTo("0 17 * * *");
        assertThat(j.deps()).extracting(c -> c.path()).containsExactly("/rates/eod/market-data");
        assertThat(j.defaultArgs()).containsEntry("curve_set", "all").containsEntry("valuation_ccy", "USD");
    }

    @Test
    void rejectsMismatchedGroup(@TempDir Path root) throws Exception {
        Path svc = root.resolve("services/rates/swaps/pricer.yaml");
        Files.createDirectories(svc.getParent());
        Files.writeString(svc, """
                group: WRONG
                project: swaps
                mainClass: com.example.PricerApp
                """);
        assertThatThrownBy(() -> new DefinitionLoader(root, Lane.DEV).load())
                .isInstanceOf(DefinitionParseException.class)
                .hasMessageContaining("doesn't match path segment");
    }

    @Test
    void emptyRoot(@TempDir Path root) {
        DefinitionLoader.Result r = new DefinitionLoader(root, Lane.DEV).load();
        assertThat(r.services()).isEmpty();
        assertThat(r.jobs()).isEmpty();
    }
}
