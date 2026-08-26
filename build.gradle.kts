import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    base
}

// demo-spring-boot is a reference host, never published (docs/versioning.md in davkit-core).
val publishedModules = setOf("server-spring-boot")

allprojects {
    group = "com.tucanoo.davkit"
    // version comes from gradle.properties; kept in lockstep across the three repositories.
}

subprojects {
    if (name in publishedModules) {
        apply(plugin = "maven-publish")
        plugins.withId("java") {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                    }
                }
            }
        }
    }
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            // Real sources for the open-source wrapper; the proprietary core publishes a
            // README-only sources jar instead (distribution hardening design).
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror", "-parameters"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
