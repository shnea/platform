plugins { java }
group = "kr.shnea.platform"
version = "0.1.0"
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    compileOnly("org.keycloak:keycloak-services:26.7.4")
    testImplementation("org.keycloak:keycloak-services:26.7.4")
    testImplementation("org.freemarker:freemarker:2.3.32")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.jboss.resteasy:resteasy-core:6.2.12.Final")
}
tasks.test {
    useJUnitPlatform()
    environment("PLATFORM_MODE", "prod")
    environment("PLATFORM_MAIL_SECRET", "test-mail-secret-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
    for (provider in listOf("NAVER", "KAKAO", "GOOGLE")) {
        environment("SOCIAL_${provider}_CLIENT_ID", "client")
        environment("SOCIAL_${provider}_CLIENT_SECRET", "secret")
    }
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
