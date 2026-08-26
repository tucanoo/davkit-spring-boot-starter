rootProject.name = "davkit-spring-boot"

include(
    "server-spring-boot",
    "demo-spring-boot",   // reference host; never published (docs/versioning.md in davkit-core)
)

// The proprietary core lives in its own repository. Use the sibling checkout when it is there so
// a core change needs no publish step; otherwise the coordinate resolves from Maven like it does
// for any outside contributor.
if (file("../davkit-core").isDirectory) {
    includeBuild("../davkit-core")
}

dependencyResolutionManagement {
    repositories {
        // Before Central carries `server`, mavenLocal is how it is found without the sibling.
        // Scoped to DavKit's own group on purpose: ~/.m2 holds Maven-installed artifacts that
        // have a POM but no Gradle module metadata, and letting those shadow real dependencies
        // silently drops the constraints that metadata carries (junit-bom, for one).
        mavenLocal {
            content { includeGroup("com.tucanoo.davkit") }
        }
        mavenCentral()
    }
}
