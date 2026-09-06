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

    // Spreadsheet import. POI is heavy, but XLSX is a zip of XML with shared
    // string tables, styled dates and a dozen edge cases — hand-rolling a reader
    // is a source of quiet wrong numbers, which is the one thing this must not be.
    implementation("org.apache.poi:poi-ooxml:5.4.0")

    // Reads the text layer out of a PDF. Real extraction for the documents that
    // have one, without needing an OCR service account (see DocumentTextExtractor).
    implementation("org.apache.pdfbox:pdfbox:3.0.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("org.testcontainers:postgresql:1.20.6")
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

    // Integration tests need Postgres and Redis. TestInfra prefers an external
    // pair when ALMIRA_TEST_* is set (CI service containers, or the local
    // docker-compose stack) and falls back to Testcontainers otherwise. Pass the
    // variables through, and help Testcontainers find Docker Desktop's socket on
    // macOS, where it lives under the user's home rather than /var/run.
    listOf(
        "ALMIRA_TEST_DB_URL", "ALMIRA_TEST_DB_OWNER_USER", "ALMIRA_TEST_DB_OWNER_PASSWORD",
        "ALMIRA_TEST_DB_APP_USER", "ALMIRA_TEST_DB_APP_PASSWORD",
        "ALMIRA_TEST_REDIS_HOST", "ALMIRA_TEST_REDIS_PORT",
    ).forEach { name -> System.getenv(name)?.let { environment(name, it) } }

    if (System.getenv("DOCKER_HOST") == null) {
        val desktopSocket = File(System.getProperty("user.home"), ".docker/run/docker.sock")
        if (desktopSocket.exists()) {
            environment("DOCKER_HOST", "unix://" + desktopSocket.absolutePath)
        }
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
