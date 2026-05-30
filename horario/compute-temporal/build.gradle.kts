plugins {
    `java-library`
}

dependencies {
    api(project(":compute-model"))
    api(project(":compute-yaml"))
    api(project(":compute-image"))
    api(project(":compute-ocp"))
    api(libs.temporal.sdk)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.temporal.testing)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}
