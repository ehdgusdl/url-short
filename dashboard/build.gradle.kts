plugins {
    id("org.springframework.boot")
}

// bootJar만 남긴다. plain jar가 같이 있으면 Dockerfile의 와일드카드 COPY가 두 파일을 잡는다.
tasks.named<Jar>("jar") { enabled = false }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
}

dependencies {
    // 셰이드된 all 아티팩트를 쓰면 HTTP 클라이언트까지 함께 들어와 별도 의존성이 필요 없다.
    implementation("com.clickhouse:clickhouse-jdbc:0.7.1-patch1:all") { isTransitive = false }
}
