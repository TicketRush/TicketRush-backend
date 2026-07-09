FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

ARG SERVICE
RUN test -n "$SERVICE" || (echo "SERVICE build arg is required (e.g. --build-arg SERVICE=auth-service)" >&2; exit 1)

COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY common ./common
COPY auth-service ./auth-service
COPY booking-service ./booking-service
COPY gateway-service ./gateway-service
COPY payment-service ./payment-service
COPY performance-service ./performance-service
COPY seat-service ./seat-service
COPY ticket-service ./ticket-service
COPY user-service ./user-service

RUN chmod +x ./gradlew
RUN ./gradlew :${SERVICE}:bootJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

# compose healthcheck용. temurin JRE 이미지에는 curl도 wget도 없다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd -r -u 10001 appuser

ARG SERVICE
COPY --from=builder /workspace/${SERVICE}/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

# 힙은 컨테이너 mem_limit 대비 비율로 잡는다(mem_limit 1g → 최대 힙 768m).
# 제한이 없으면 JVM이 호스트 RAM의 25%를 최대 힙으로 잡아 8개 서비스가 호스트를 초과한다.
# ponytail: GC/JIT 옵션은 넣지 않는다. 처리량이 흔들려 부하 테스트 수치를 왜곡한다(JDK 21 기본 G1 유지).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
# exec가 없으면 PID 1이 sh가 되어 SIGTERM이 JVM에 전달되지 않는다(graceful shutdown 무력화).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]