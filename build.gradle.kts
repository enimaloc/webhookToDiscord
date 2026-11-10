plugins {
    id("java")
    application
}

group = "fr.enimaloc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "fr.enimaloc.w2d.App"
    applicationDefaultJvmArgs = listOf("-Dpolyglot.engine.WarnInterpreterOnly=false", "--sun-misc-unsafe-memory-access=allow")
}

dependencies {
    implementation("io.javalin:javalin:7.2.4")
    implementation("org.graalvm.polyglot:polyglot:25.5.5.0.3")
    runtimeOnly("org.graalvm.polyglot:js-community:25.5.5.0.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.23.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.23.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:13.12.1")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:13.12.1")
    implementation("org.jdbi:jdbi3-core:3.55.0")
    implementation("org.jdbi:jdbi3-postgres:3.55.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.20")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.javalin:javalin-testtools:7.2.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dpolyglot.engine.WarnInterpreterOnly=false", "--sun-misc-unsafe-memory-access=allow")
}
