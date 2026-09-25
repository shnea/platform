plugins { java }
group = "kr.shnea.platform"
version = "0.1.0"
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    compileOnly("org.keycloak:keycloak-services:26.7.4")
    testImplementation("org.keycloak:keycloak-services:26.7.4")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
