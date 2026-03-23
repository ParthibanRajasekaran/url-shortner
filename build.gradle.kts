plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("java")
    id("jacoco")
    id("org.sonarqube") version "7.2.3.7755"
}

group = "org.example"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Prometheus metrics export
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql")

    // Liquibase for schema migrations
    implementation("org.liquibase:liquibase-core")

    // Redisson (Redis client) - replaces spring-data-redis
    implementation("org.redisson:redisson-spring-boot-starter:3.36.0")

    // Base62 encoding
    implementation("io.seruco.encoding:base62:0.1.3")

    // Bucket4j for rate limiting (in-process, per-IP)
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.1"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
    }
}

sonar {
    properties {
        property("sonar.projectKey", "ParthibanRajasekaran_url-shortner")
        property("sonar.organization", "parthibanrajasekaran")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.java.source", "21")
        property("sonar.exclusions", "**/dto/**,**/entity/**,**/exception/**,**/*Application.java")
    }
}
