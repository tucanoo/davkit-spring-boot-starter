// Not "davkit-spring-boot": that name belongs to the published module below, and a root project
// sharing a subproject's name makes task paths ambiguous. Same reasoning as davkit-grails-plugin.
rootProject.name = "davkit-spring"

include(
    "server-spring-boot",
    "demo-spring-boot",   // reference host; never published
)

// The directory keeps its role-name; the published coordinate is com.tucanoo.davkit:davkit-spring-boot
// (group from the root build, artifactId from here). Renamed 2026-08-29 with the core, before the
// first Central release, so all three coordinates match their repository names.
//
// Renaming the Gradle project rather than only the publication's artifactId is deliberate: the
// Grails repository includes this build as a composite and substitutes on the project's own
// coordinates, so the two must agree.
project(":server-spring-boot").name = "davkit-spring-boot"

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
