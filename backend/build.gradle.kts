plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.spring") version "2.1.21"
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "tech.bhrigu.almira"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Deliberately spring-jdbc rather than JPA. This service leans on
    // PostgreSQL features an ORM fights with: row-level security that depends
    // on a per-transaction GUC, jsonb attributes, numeric money, deferred
    // constraints, and views. Explicit SQL keeps all of that visible.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
}

// The migrations live at db/migrations in the repo root -- one source of truth
// shared by the local psql workflow, Flyway at runtime, and Testcontainers.
// Copying them into the jar keeps the artifact self-contained.
val syncMigrations by tasks.registering(Sync::class) {
    from(file("../db/migrations"))
    into(layout.buildDirectory.dir("generated/migrations/db/migration"))
}

sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/migrations")) }
tasks.named("processResources") { dependsOn(syncMigrations) }

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
