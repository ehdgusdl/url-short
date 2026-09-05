# ---- Build Stage ----
FROM gradle:8.11.1-jdk21 AS builder

ARG MODULE

WORKDIR /workspace

# 멀티모듈 빌드라 루트 설정 + 전체 모듈(common 포함)이 필요 → 통째로 복사
COPY . .
RUN gradle :${MODULE}:bootJar --no-daemon

# ---- Runtime Stage ----
# JDK를 쓰는 이유: 컨테이너 안에서 jcmd/jmap으로 실제 힙을 계측하기 위해서다.
FROM eclipse-temurin:21-jdk

ARG MODULE

WORKDIR /app

COPY --from=builder /workspace/${MODULE}/build/libs/*.jar app.jar

EXPOSE 8080

# JAVA_TOOL_OPTIONS 대신 JAVA_OPTS를 쓴다. 전자는 컨테이너 안에서 실행하는 jcmd/jmap에도 적용돼
# JMX 포트를 두 번 열려다 실패한다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
