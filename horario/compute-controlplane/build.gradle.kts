plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":compute-model"))
    implementation(project(":compute-yaml"))
    implementation(project(":compute-image"))
    implementation(project(":compute-ocp"))
    implementation(project(":compute-temporal"))

    implementation(libs.vertx.core)
    implementation(libs.vertx.web)
    implementation(libs.vertx.web.client)

    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.temporal.testing)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}

application {
    mainClass.set("com.compute.cp.Bootstrap")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootDir   // so relative paths like deploy/definitions resolve from the repo root
}

tasks.register<Jar>("uberJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(mapOf("Main-Class" to "com.compute.cp.Bootstrap"))
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    // skip module-info.class collisions from various deps
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

tasks.named("build") {
    dependsOn("uberJar")
}
