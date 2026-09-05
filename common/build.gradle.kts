plugins {
    `java-library`
}

// 세 서비스가 공유하는 도메인·영속·ID·DataSource 라우팅·캐시 채널 상수.
dependencies {
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.boot:spring-boot-starter-validation")
    // L2 캐시 값(UrlView) JSON 직렬화. 두 서비스가 같은 포맷을 써야 하므로 common이 들고 간다.
    api("org.springframework.boot:spring-boot-starter-json")
    runtimeOnly("com.mysql:mysql-connector-j")
}
