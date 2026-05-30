plugins {
    `java-library`
}

dependencies {
    api(project(":compute-model"))
    implementation(libs.snakeyaml)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}
