# Build dependencies and sources without copying the working directory or secrets.
FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src/main src/main
RUN mvn -B -Dmaven.test.skip=true package && javac -d /probe src/main/java/com/example/shortlink/observability/CoreReadinessProbe.java
FROM eclipse-temurin:17.0.14_7-jre-jammy@sha256:d1ee8c02785c19c525d3af2f1ee8a51ebe85e3f55e1e02f7dcb9cbfe4354d90c
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/short-link-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=build --chown=10001:10001 /probe /app/probe
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
