plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":compute-model"))
    implementation(project(":compute-temporal"))
    implementation(libs.temporal.sdk)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.compute.subtask.WorkerMain")
}

// The subtask worker is an ephemeral OCP Job pod — it needs TASK_ID from env.
// Disable the Gradle run task so it doesn't fail when running the root project.
tasks.named("run") {
    enabled = false
}

tasks.register<Jar>("uberJar") {
    archiveClassifier.set("all")
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

tasks.named("build") {
    dependsOn("uberJar")
}
