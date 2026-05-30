plugins {
    `java-library`
}

dependencies {
    api(project(":compute-model"))
    api(libs.vertx.core)
    implementation(libs.vertx.web.client)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.vertx.web)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}
