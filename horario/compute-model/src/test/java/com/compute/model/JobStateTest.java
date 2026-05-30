package com.compute.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobStateTest {

    private static final ComputeId ID = ComputeId.of("/rates/eod/curve-build");

    private static JobState fresh() {
        return new JobState(
                ID, Lane.DEV, "rates", "eod",
                LocalDate.of(2026, 5, 25), JobStatus.SCHEDULED,
                Instant.now(), null, null, 0, "", Map.of("k", "v"), false);
    }

    @Test
    void withStatusPreservesFields() {
        JobState s = fresh().withStatus(JobStatus.WAITING_DEPS, "waiting");
        assertThat(s.status()).isEqualTo(JobStatus.WAITING_DEPS);
        assertThat(s.message()).isEqualTo("waiting");
        assertThat(s.id()).isEqualTo(ID);
        assertThat(s.group()).isEqualTo("rates");
        assertThat(s.args()).containsEntry("k", "v");
    }

    @Test
    void withStatusSetsStartTimeOnStarting() {
        JobState s = fresh().withStatus(JobStatus.STARTING, "starting");
        assertThat(s.startTime()).isNotNull();
    }

    @Test
    void withStatusSetsStartTimeOnRunning() {
        JobState s = fresh().withStatus(JobStatus.RUNNING, "running");
        assertThat(s.startTime()).isNotNull();
    }

    @Test
    void withStatusDoesNotOverwriteExistingStartTime() {
        Instant fixed = Instant.parse("2026-05-25T10:00:00Z");
        JobState started = new JobState(
                ID, Lane.DEV, "rates", "eod",
                LocalDate.of(2026, 5, 25), JobStatus.RUNNING,
                Instant.now(), fixed, null, 0, "", Map.of(), false);
        JobState s = started.withStatus(JobStatus.RUNNING, "still running");
        assertThat(s.startTime()).isEqualTo(fixed);
    }

    @Test
    void withStatusSetsEndTimeOnTerminal() {
        JobState s = fresh().withStatus(JobStatus.COMPLETED, "done");
        assertThat(s.endTime()).isNotNull();

        JobState f = fresh().withStatus(JobStatus.FAILED, "error");
        assertThat(f.endTime()).isNotNull();

        JobState sk = fresh().withStatus(JobStatus.SKIPPED, "skipped");
        assertThat(sk.endTime()).isNotNull();
    }

    @Test
    void withStatusKeepsEndTimeNullForNonTerminal() {
        JobState s = fresh().withStatus(JobStatus.WAITING_DEPS, "waiting");
        assertThat(s.endTime()).isNull();
    }

    @Test
    void withExitUpdatesCodeAndMessage() {
        JobState s = fresh().withExit(42, "exit-msg");
        assertThat(s.exitCode()).isEqualTo(42);
        assertThat(s.message()).isEqualTo("exit-msg");
        assertThat(s.status()).isEqualTo(JobStatus.SCHEDULED); // status unchanged
    }

    @Test
    void stateIsImmutable() {
        JobState original = fresh();
        original.withStatus(JobStatus.RUNNING, "x");
        assertThat(original.status()).isEqualTo(JobStatus.SCHEDULED);
    }
}
