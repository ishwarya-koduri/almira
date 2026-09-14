import java.security.MessageDigest


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

    // Passkeys (WebAuthn): attestation and assertion verification, COSE keys,
    // signature counters, origin and RP-ID checks. Yubico's server library is
    // the maintained reference implementation; this is exactly the kind of
    // parsing and signature code that must not be hand-rolled. It makes no
    // network calls: metadata-service lookups live in a separate artifact that
    // is not used (auth/PasskeyService.kt).
    implementation("com.yubico:webauthn-server-core:2.9.0") {
        // Declared by the library and referenced by none of its classes. Left in,
        // Spring Boot would see Apache HttpClient on the classpath and quietly
        // switch the HTTP client every provider adapter is built on.
        exclude(group = "org.apache.httpcomponents.client5", module = "httpclient5")
    }
    // Live email over SMTP (provider/SmtpEmailSender.kt). Spring's own mail
    // support over Jakarta Mail, the version pinned by the Spring Boot BOM above,
    // so any relay a deployment chooses — SES, Postmark, a company server — is
    // configuration rather than a vendor SDK. Nothing connects unless
    // almira.providers.email.mode is live.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // Object storage for documents, used only when almira.storage.provider=s3
    // (the filesystem stays the default). The S3 module alone, pinned; the
    // async Netty client is excluded because only the synchronous client is used.
    implementation("software.amazon.awssdk:s3:2.54.17") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    // QR codes on the printed emergency kit and the envelope edition of the
    // handbook (continuity/QrCode.kt). ZXing's core module only: pure Java, no
    // dependencies of its own, no network, and the reference encoder phone
    // scanners are tested against. Reed-Solomon and mask scoring are exactly
    // the kind of code that should not be hand-rolled for a page kept for years.
    implementation("com.google.zxing:core:3.5.3")

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

// The service worker serves the web shell cache-first, versioned by VERSION in
// static/sw.js, so a shell change a browser has already cached stays invisible
// until that constant changes (known-issues 3). Remembering to bump it by hand
// failed in practice. The build now appends a fingerprint of every other static
// file to it, so any change to an asset changes the worker's bytes and its cache
// name. The hand-written part stays, and may still be bumped; it no longer has to be.
// The same fingerprint is recomputed by ServiceWorkerVersionTest.
val shellFingerprint by tasks.registering {
    val staticDir = file("src/main/resources/static")
    val out = layout.buildDirectory.file("generated/shell-fingerprint.txt")
    inputs.dir(staticDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(out)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        staticDir.walkTopDown()
            .filter { it.isFile && it.relativeTo(staticDir).invariantSeparatorsPath != "sw.js" }
            .sortedBy { it.relativeTo(staticDir).invariantSeparatorsPath }
            .forEach { f ->
                digest.update(f.relativeTo(staticDir).invariantSeparatorsPath.toByteArray())
                digest.update(0)
                digest.update(f.readBytes())
                digest.update(0)
            }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }.take(12)
        out.get().asFile.writeText(hex)
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(syncMigrations, shellFingerprint)
    val fingerprintFile = shellFingerprint.map { it.outputs.files.singleFile }
    inputs.file(fingerprintFile)
    val versionLine = Regex("""^const VERSION = "([^"+]+)(\+[0-9a-f]*)?";$""")
    filesMatching("static/sw.js") {
        val fingerprint = fingerprintFile.get().readText().trim()
        filter { line ->
            versionLine.matchEntire(line)
                ?.let { "const VERSION = \"${it.groupValues[1]}+$fingerprint\";" }
                ?: line
        }
    }
    // Loud, not quiet: a renamed or reformatted VERSION line would otherwise
    // ship a worker whose cache never turns over.
    doLast {
        val fingerprint = fingerprintFile.get().readText().trim()
        val built = destinationDir.resolve("static/sw.js")
        val stamped = built.readLines().count { it.endsWith("+$fingerprint\";") && it.startsWith("const VERSION = ") }
        if (stamped != 1) {
            throw GradleException(
                "static/sw.js must have exactly one line `const VERSION = \"almira-vN\";` " +
                    "for the build to stamp the shell fingerprint onto; found $stamped (known-issues 3).",
            )
        }
    }
}

// `bootRun` is the development launcher, so it chooses development explicitly.
// The checks that relax in development (docs/17 §3) refuse on a MISSING
// ALMIRA_ENV, so a jar started anywhere without the variable fails closed —
// but a developer running ./scripts/dev.sh should not have to know that.
// An ALMIRA_ENV already in the shell wins.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment("ALMIRA_ENV", System.getenv("ALMIRA_ENV") ?: "development")
}

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
        "ALMIRA_TEST_REDIS_HOST", "ALMIRA_TEST_REDIS_PORT", "ALMIRA_TEST_CHECKSUMS_DB_URL",
        "ALMIRA_TEST_S3_ENDPOINT",
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
