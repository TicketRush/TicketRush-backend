FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

ARG SERVICE

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
RUN ./gradlew :${SERVICE}:clean :${SERVICE}:bootJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

ARG SERVICE

COPY --from=builder /workspace/${SERVICE}/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]