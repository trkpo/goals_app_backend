plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.2.1"
}

group = "ru.trkpo"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        target(
            "src/main/java/**/*.java",
            "src/test/java/**/*.java",
            "src/dbTools/java/**/*.java",
        )
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    enabled = false
}

sourceSets {
    create("dbTools")
}

tasks.register<JavaExec>("dbRollback") {
    group = "database"
    description = "Rolls back applied migrations in reverse order"
    classpath = sourceSets.main.get().runtimeClasspath + sourceSets["dbTools"].output
    mainClass.set("ru.trkpo.goals.dbtools.RollbackRunner")
    args("db/rollback")
}

tasks.register<Exec>("dbUp") {
    group = "database"
    description = "Starts the local PostgreSQL container"
    commandLine("docker", "compose", "up", "-d", "db")
}

tasks.register<Exec>("dbDown") {
    group = "database"
    description = "Stops local containers without removing the database volume"
    commandLine("docker", "compose", "down")
}

tasks.named("check") {
    dependsOn("dbToolsClasses")
}
