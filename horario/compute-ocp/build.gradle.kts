plugins {
    `java-library`
}

dependencies {
    api(project(":compute-model"))
    api(libs.fabric8.kubernetes)
    api(libs.fabric8.openshift)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.fabric8.mockserver)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}
