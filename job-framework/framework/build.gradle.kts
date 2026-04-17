plugins {
    application
}

dependencies {
    // Vert.x core + web for the HTTP submission API
    implementation(platform("io.vertx:vertx-stack-depchain:4.5.10"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web")
    implementation("io.vertx:vertx-config")

    // fabric8 — OCP/K8s client with CronJob, Job, Deployment, Informer support
    implementation(platform("io.fabric8:kubernetes-client-bom:6.13.4"))
    implementation("io.fabric8:kubernetes-client")
    implementation("io.fabric8:openshift-client")

    // Cron parsing (time-of-day is just a degenerate cron)
    implementation("com.cronutils:cron-utils:9.2.1")

    // JSON (definitions over the wire)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.8")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("io.vertx:vertx-junit5")
}

application {
    mainClass.set("io.acme.orchestrator.OrchestratorMain")
}
