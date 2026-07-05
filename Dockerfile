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

RUN useradd -r -u 10001 appuser

ARG SERVICE
COPY --from=builder /workspace/${SERVICE}/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]