plugins {
    `java-library`
}

description = "DavKit Spring Boot starter: auto-configuration, properties, servlet/filter registration."

dependencies {
    // The proprietary core, resolved from Maven; substituted by the sibling checkout when
    // davkit-core is present (see settings.gradle.kts). Exact version, never a range.
    api("com.tucanoo.davkit:davkit-server:$version")

    // Compiled against Boot 3.x. Boot 4.x compatibility is verified by a separate compilation
    // pass; if packages diverge the starter is split.
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.jakarta.servlet)
    // Only for the StrictHttpFirewall bean; guarded by @ConditionalOnClass, so hosts
    // without Spring Security are unaffected.
    compileOnly(libs.spring.security.web)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.security.web)
    testImplementation("com.h2database:h2:2.3.232") // JdbcDavLockStore tests live here so `server` stays dependency-free
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Compile the same sources against Spring Boot 4 on every build, so a Boot 4 package
// move fails here rather than at a customer's site. `./gradlew :davkit-spring-boot-starter:compileBoot4`.
val boot4 by configurations.creating {
    extendsFrom(configurations.getByName("api"))
}
dependencies {
    boot4("org.springframework.boot:spring-boot-autoconfigure:4.1.0")
    boot4("org.springframework.boot:spring-boot:4.1.0")
    boot4("jakarta.servlet:jakarta.servlet-api:6.1.0")
    boot4("org.springframework.security:spring-security-web:7.1.0")
}
val compileBoot4 by tasks.registering(JavaCompile::class) {
    group = "verification"
    description = "Compiles the starter against Spring Boot 4.x."
    source = sourceSets.main.get().java
    classpath = boot4
    destinationDirectory.set(layout.buildDirectory.dir("classes/boot4"))
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}
tasks.named("check") { dependsOn(compileBoot4) }
