package com.compute.cp;

import com.compute.model.Lane;
import com.compute.model.LaneRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LaneCatalogTest {

    @Test
    void emptyRootProducesNoTriples(@TempDir Path root) {
        List<LaneRef> refs = new LaneCatalog(root).discover();
        assertThat(refs).isEmpty();
    }

    @Test
    void discoverPicksUpServiceTriples(@TempDir Path root) throws Exception {
        Path svc = root.resolve("services/rates/swaps/pricer.yaml");
        Files.createDirectories(svc.getParent());
        Files.writeString(svc, """
                group: rates
                project: swaps
                mainClass: com.example.Pricer
                """);

        List<LaneRef> refs = new LaneCatalog(root).discover();
        // One entry per lane (DEV/SIT/UAT/PFIX/PROD) all pointing to rates/swaps
        Set<String> namespaces = refs.stream()
                .map(LaneRef::temporalNamespace).collect(Collectors.toSet());
        assertThat(namespaces).contains("rates-swaps-dev", "rates-swaps-prod");
        assertThat(refs.stream().map(LaneRef::group).collect(Collectors.toSet()))
                .containsExactly("rates");
    }

    @Test
    void discoverPicksUpJobTriples(@TempDir Path root) throws Exception {
        Path job = root.resolve("jobs/fx/eod/snap.yaml");
        Files.createDirectories(job.getParent());
        Files.writeString(job, """
                group: fx
                project: eod
                mainClass: com.example.Snap
                """);

        List<LaneRef> refs = new LaneCatalog(root).discover();
        assertThat(refs.stream().map(LaneRef::group).collect(Collectors.toSet()))
                .containsExactly("fx");
        assertThat(refs.stream().map(LaneRef::project).collect(Collectors.toSet()))
                .containsExactly("eod");
    }

    @Test
    void discoverDeduplicatesTriples(@TempDir Path root) throws Exception {
        // Two definitions under the same group/project — should produce one triple per lane
        Path svc = root.resolve("services/rates/swaps/pricer.yaml");
        Path job = root.resolve("jobs/rates/swaps/curve.yaml");
        Files.createDirectories(svc.getParent());
        Files.createDirectories(job.getParent());
        Files.writeString(svc, "group: rates\nproject: swaps\nmainClass: com.example.Pricer\n");
        Files.writeString(job, "group: rates\nproject: swaps\nmainClass: com.example.Curve\n");

        List<LaneRef> refs = new LaneCatalog(root).discover();
        long devCount = refs.stream()
                .filter(r -> r.lane() == Lane.DEV && r.group().equals("rates"))
                .count();
        // Set-based dedup: only one rates/swaps/DEV entry
        assertThat(devCount).isEqualTo(1);
    }

    @Test
    void discoverProducesAllLanes(@TempDir Path root) throws Exception {
        Path svc = root.resolve("services/core/auth/service.yaml");
        Files.createDirectories(svc.getParent());
        Files.writeString(svc, "group: core\nproject: auth\nmainClass: com.example.Auth\n");

        List<LaneRef> refs = new LaneCatalog(root).discover();
        Set<Lane> lanes = refs.stream().map(LaneRef::lane).collect(Collectors.toSet());
        assertThat(lanes).containsExactlyInAnyOrder(Lane.values());
    }
}
