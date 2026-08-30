// demo-spring-boot: click "Edit in Word" → Word → edit → save → H2 row updated.
// The reference host for the Spring Boot starter. Intentionally small: one entity, one provider,
// one page. Form login, licence-derived signed URLs and MS-OFBA are all wired up.
plugins {
    java
    id("org.springframework.boot") version "3.5.4"
}

dependencies {
    implementation(project(":davkit-spring-boot"))
    implementation(libs.spring.boot.starter.web)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.5.4")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf:3.5.4")
    implementation("org.springframework.boot:spring-boot-starter-security:3.5.4")
    runtimeOnly("com.h2database:h2:2.3.232")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
