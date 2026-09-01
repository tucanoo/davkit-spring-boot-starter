import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import java.util.zip.ZipFile

plugins {
    base
}

// demo-spring-boot is a reference host, never published.
val publishedModules = setOf("davkit-spring-boot-starter")

// Central rejects -SNAPSHOT on the release endpoint. The guard still runs on snapshots — that is
// what keeps it exercised between releases — but the signature check switches on here.
val isRelease = !version.toString().endsWith("-SNAPSHOT")

val productUrl = "https://tucanoo.com/products/davkit"
val repoUrl = "https://github.com/tucanoo/davkit-spring-boot-starter"
val licenseName = "The Apache License, Version 2.0"

allprojects {
    group = "com.tucanoo.davkit"
    // version comes from gradle.properties; kept in lockstep across the three repositories.
}

// The exact bytes destined for Central: a local Maven-layout directory, so the guard inspects the
// real signed artifacts and the same directory becomes the Portal upload bundle.
val stagingRepoDir = layout.buildDirectory.dir("staging-deploy")

val cleanStagingRepo by tasks.registering(Delete::class) {
    description = "Empties the staging repository so a stale version cannot ride along in the bundle."
    delete(stagingRepoDir)
}

val stageRelease by tasks.registering {
    group = "publishing"
    description = "Cleans and repopulates build/staging-deploy with the signed release artifacts."
    dependsOn(cleanStagingRepo)
}

subprojects {
    if (name in publishedModules) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        plugins.withId("java") {
            extensions.configure<JavaPluginExtension> {
                // Real sources for the open-source wrapper; the guard checks they are actually
                // there, because an empty sources jar would pass Central and help nobody.
                withJavadocJar()
            }
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                        pom {
                            name.set("DavKit Spring Boot starter")
                            // Lazy: subprojects {} runs before the module's own script sets this.
                            description.set(project.provider { project.description ?: "DavKit Spring Boot starter" })
                            url.set(repoUrl)
                            licenses {
                                license {
                                    name.set(licenseName)
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                    distribution.set("repo")
                                }
                            }
                            developers {
                                developer {
                                    id.set("dave")
                                    name.set("David Brown")
                                    email.set("dave@tucanoo.com")
                                    organization.set("Tucanoo Solutions Ltd")
                                    organizationUrl.set("https://tucanoo.com")
                                }
                            }
                            scm {
                                connection.set("scm:git:https://github.com/tucanoo/davkit-spring-boot-starter.git")
                                developerConnection.set("scm:git:ssh://git@github.com/tucanoo/davkit-spring-boot-starter.git")
                                url.set(repoUrl)
                            }
                            // The wrapper is Apache 2.0 but the core it depends on is not; say so
                            // here rather than leaving a consumer to discover it at runtime.
                            organization {
                                name.set("Tucanoo Solutions Ltd")
                                url.set(productUrl)
                            }
                        }
                    }
                }
                repositories {
                    maven {
                        name = "staging"
                        url = rootProject.layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
                    }
                }
            }
            extensions.configure<SigningExtension> {
                // Delegates to the local gpg, so the passphrase reaches gpg-agent and never a
                // Gradle property or a file. Set signing.gnupg.keyName in ~/.gradle/gradle.properties
                // (and signing.gnupg.executable=gpg on MacGPG2). Not required for snapshots, so a
                // clean contributor checkout still builds without a key.
                useGpgCmd()
                isRequired = isRelease
                sign(extensions.getByType<PublishingExtension>().publications["maven"])
            }

            val publishToStaging = tasks.named("publishMavenPublicationToStagingRepository")
            publishToStaging.configure { mustRunAfter(cleanStagingRepo) }
            stageRelease.configure { dependsOn(publishToStaging) }
        }
    }
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            // Real sources for the open-source wrapper; the proprietary core registers no sources
            // variant and ships only an explanatory stub (distribution hardening design).
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(17)
            options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror", "-parameters"))
        }
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                encoding = "UTF-8"
                addBooleanOption("Xdoclint:all,-missing", true)
                addStringOption("Xmaxwarns", "1000")
                quiet()
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

// ---------------------------------------------------------------------------
// Release artifact guard
//
// This repository must publish real sources under Apache 2.0, must depend on the core at the
// exact matching version, and must never ship the demo host or test material.
// ---------------------------------------------------------------------------

val forbiddenEntries = listOf(
    "com/tucanoo/davkit/license/cli/" to "license-cli classes",
    "captures/" to "recorded Office traffic",
    "demo/" to "demo host classes",
    "application.yml" to "a host application config",
    ".env" to "an environment file",
)

val pemPrivateKey = Regex("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----")

val verifyReleaseArtifacts by tasks.registering {
    group = "verification"
    description = "Inspects the staged Central artifacts for licence, dependency and packaging mistakes."
    dependsOn(stageRelease)

    val reportFile = layout.buildDirectory.file("reports/release-artifacts/inventory.txt")
    val stagingDir = stagingRepoDir
    val versionString = version.toString()
    val release = isRelease
    val expectedLicence = licenseName

    outputs.file(reportFile)
    // Never up to date: this task's real inputs are the staged artifacts, which Gradle cannot
    // see through the publish tasks. Left to infer, it can skip verification on a later run —
    // and a guard that can be skipped is not a guard.
    outputs.upToDateWhen { false }

    doLast {
        val failures = mutableListOf<String>()
        val inventory = StringBuilder("DavKit Spring Boot starter release artifacts — version $versionString\n\n")

        val moduleDir = stagingDir.get().asFile
            .resolve("com/tucanoo/davkit/davkit-spring-boot-starter/$versionString")
        if (!moduleDir.isDirectory) {
            throw GradleException("No staged artifacts at $moduleDir — run stageRelease first.")
        }

        val files = moduleDir.listFiles()!!.sortedBy { it.name }
        val names = files.map { it.name }.toSet()

        // Snapshots carry a unique timestamped name, releases the plain version; derive from the POM.
        val pomFile = files.singleOrNull { it.name.endsWith(".pom") }
            ?: throw GradleException("Expected exactly one .pom in $moduleDir")
        val base = pomFile.name.removeSuffix(".pom")

        // 1. Everything Central expects, sources included — this artifact is open source.
        val required = listOf("$base.jar", "$base-sources.jar", "$base-javadoc.jar", "$base.pom", "$base.module")
        required.filterNot { it in names }.forEach { failures += "missing required artifact: $it" }

        required.filter { it in names }.forEach { artifact ->
            listOf("md5", "sha1").forEach { sum ->
                if ("$artifact.$sum" !in names) failures += "missing checksum: $artifact.$sum"
            }
            if (release && "$artifact.asc" !in names) failures += "missing signature: $artifact.asc"
        }

        // 2. The sources jar carries real source, not an empty shell.
        val sourcesJar = moduleDir.resolve("$base-sources.jar")
        if (sourcesJar.isFile) {
            val javaFiles = ZipFile(sourcesJar).use { zip ->
                zip.entries().toList().count { it.name.endsWith(".java") }
            }
            if (javaFiles == 0) failures += "the sources jar contains no Java source"
            inventory.append("$base-sources.jar — $javaFiles Java sources\n")
        }

        // 3. Nothing internal or host-specific inside the archives.
        files.filter { it.name.endsWith(".jar") }.forEach { jar ->
            ZipFile(jar).use { zip ->
                val entries = zip.entries().toList()
                inventory.append("${jar.name} — ${entries.size} entries\n")
                entries.forEach { entry ->
                    forbiddenEntries.forEach { (needle, what) ->
                        if (entry.name.contains(needle)) {
                            failures += "${jar.name} contains $what: ${entry.name}"
                        }
                    }
                    if (entry.name.endsWith("Test.class") || entry.name.endsWith("Tests.class") ||
                        entry.name.endsWith("IT.class")
                    ) {
                        failures += "${jar.name} contains test classes: ${entry.name}"
                    }
                    if (!entry.isDirectory && entry.size in 1L..1_048_576L) {
                        val text = zip.getInputStream(entry).readBytes().toString(Charsets.ISO_8859_1)
                        if (pemPrivateKey.containsMatchIn(text)) {
                            failures += "${jar.name} contains a PEM private key: ${entry.name}"
                        }
                    }
                }
            }
        }

        // 4. Apache 2.0, and the core pinned to this exact version — never a range. A mismatch here
        //    is the failure the lockstep scheme exists to prevent.
        val text = pomFile.readText()
        if (!text.contains("<name>$expectedLicence</name>")) {
            failures += "wrapper POM does not declare $expectedLicence"
        }
        if (text.contains("DavKit Commercial")) {
            failures += "wrapper POM declares the commercial licence — wrong licence policy for the Apache 2.0 wrapper"
        }
        val coreDep = Regex(
            """<groupId>com\.tucanoo\.davkit</groupId>\s*<artifactId>davkit-server</artifactId>\s*<version>([^<]+)</version>"""
        ).find(text)
        when {
            coreDep == null -> failures += "wrapper POM does not depend on com.tucanoo.davkit:davkit-server"
            coreDep.groupValues[1] != versionString ->
                failures += "wrapper POM depends on core ${coreDep.groupValues[1]}, expected $versionString"
        }

        files.forEach { inventory.append("  ${it.name}  (${it.length()} bytes)\n") }
        reportFile.get().asFile.apply { parentFile.mkdirs() }.writeText(inventory.toString())

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Release artifact guard failed:\n" + failures.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle("Release artifact guard passed. Inventory: ${reportFile.get().asFile}")
    }
}

val centralBundle by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Zips the verified staging repository into a Central Portal upload bundle."
    dependsOn(verifyReleaseArtifacts)
    // maven-metadata is a repository index, not a release artifact; the Portal builds its own.
    from(stagingRepoDir) { exclude("**/maven-metadata.xml*") }
    archiveFileName.set("davkit-spring-boot-starter-central-$version.zip")
    destinationDirectory.set(layout.buildDirectory.dir("central"))
    doLast {
        logger.lifecycle("Upload bundle: ${archiveFile.get().asFile}")
    }
}
