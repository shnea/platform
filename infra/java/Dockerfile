# syntax=docker/dockerfile:1
FROM gradle:9.7.1-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY services/ services/
ARG SERVICE
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked gradle --no-daemon :services:${SERVICE}:build && cp services/${SERVICE}/build/libs/*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
