plugins {
    application
}

dependencies {
    implementation(project(":examples:shared-lib"))
}

application {
    mainClass.set("io.acme.example.job.ReportJob")
}
