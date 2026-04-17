plugins {
    application
}

dependencies {
    implementation(project(":examples:shared-lib"))

    // Vert.x for the HTTP side of the example service
    implementation(platform("io.vertx:vertx-stack-depchain:4.5.10"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web")
}

application {
    mainClass.set("io.acme.example.service.GreeterService")
}
