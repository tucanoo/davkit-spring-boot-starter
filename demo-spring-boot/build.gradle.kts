// demo-spring-boot: click "Edit in Word" → Word → edit → save → Postgres row updated.
// The reference host for the Spring Boot starter. Intentionally small: one entity, one provider,
// one page. Auth is v0.2, so the demo is anonymous — run it on localhost / a private network only.
plugins {
    java
    id("org.springframework.boot") version "3.5.4"
}

dependencies {
    implementation(project(":server-spring-boot"))
    implementation(libs.spring.boot.starter.web)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.5.4")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf:3.5.4")
    implementation("org.springframework.boot:spring-boot-starter-security:3.5.4")
    runtimeOnly("org.postgresql:postgresql:42.7.7")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.4")
    testRuntimeOnly("com.h2database:h2:2.3.232")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
