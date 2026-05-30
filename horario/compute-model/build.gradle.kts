plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
