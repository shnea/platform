plugins { `java-library` }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
}
