plugins {
    java
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Avro
    implementation("org.apache.avro:avro:1.11.3")

    // Eclipse Collections
    implementation("org.eclipse.collections:eclipse-collections-api:11.1.0")
    implementation("org.eclipse.collections:eclipse-collections:11.1.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

avro {
    templateDirectory.set(file("src/main/resources/org/apache/avro/compiler/specific/templates"))
    isCreateSetters = false
    fieldVisibility = "PRIVATE"
    stringType = "String"
}

tasks.test {
    useJUnitPlatform()
}
