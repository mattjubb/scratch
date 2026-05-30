rootProject.name = "vasara"

include(
    "compute-model",
    "compute-yaml",
    "compute-image",
    "compute-ocp",
    "compute-temporal",
    "compute-controlplane",
    "compute-subtask-worker",
    "examples:random-numbers",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
