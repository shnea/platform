plugins {
    id("org.springframework.boot") version "4.1.1" apply false
}

allprojects {
    group = "kr.shnea.platform"
    version = "0.1.0-SNAPSHOT"
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
