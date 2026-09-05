plugins {
    id("org.springframework.boot")
}

// bootJar만 남긴다. plain jar가 같이 있으면 Dockerfile의 와일드카드 COPY가 두 파일을 잡는다.
tasks.named<Jar>("jar") { enabled = false }

dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
