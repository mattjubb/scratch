/**
 * Random-numbers example — a worker image that implements SubtaskActivities and a
 * standalone client that submits a 1 000-subtask job to the control plane.
 *
 * Build targets
 * ─────────────
 *   ./gradlew :examples:random-numbers:workerJar   → build/libs/random-numbers-worker.jar
 *       Fat jar (entrypoint WorkerMain) ready to COPY into a Docker image.
 *
 *   ./gradlew :examples:random-numbers:submitJar   → build/libs/random-numbers-submit.jar
 *       Standalone jar that submits 1 000 subtasks to the control plane.
 *       Run with:  java -jar build/libs/random-numbers-submit.jar [base-url] [lane] [count]
 */
plugins {
    java
}

dependencies {
    // Worker-side: SPI implementation lives here; WorkerMain is the entrypoint.
    implementation(project(":compute-model"))
    implementation(project(":compute-temporal"))
    implementation(project(":compute-subtask-worker"))
    implementation(libs.temporal.sdk)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

// ── Worker uber-jar (runs inside the OCP Job pod) ───────────────────────────
tasks.register<Jar>("workerJar") {
    group = "build"
    description = "Fat jar for the random-numbers subtask worker image"
    archiveFileName.set("random-numbers-worker.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(mapOf("Main-Class" to "com.compute.subtask.WorkerMain"))
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

// ── Submit uber-jar (runs on the developer's laptop) ────────────────────────
tasks.register<Jar>("submitJar") {
    group = "build"
    description = "Standalone fat jar that submits 1 000 subtasks to the control plane"
    archiveFileName.set("random-numbers-submit.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(mapOf("Main-Class" to "com.example.randomnumbers.SubmitTask"))
    }
    // SubmitTask only uses java.net.http — no extra runtime deps needed.
    from(sourceSets.main.get().output)
}

tasks.named("build") {
    dependsOn("workerJar", "submitJar")
}

// Tests for the activity logic (no Temporal server needed — pure unit tests)
dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test {
    useJUnitPlatform()
}
