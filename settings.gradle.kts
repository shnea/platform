pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}

rootProject.name = "platform"
include("services:project-service", "services:file-service", "services:notification-service")
include("libraries:http")
