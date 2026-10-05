FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/blueai-auth-api-*.jar app.jar
# The runtime user is unprivileged, so the upload directory must exist and be writable by it.
RUN command -v wget \
    && mkdir -p /app/storage/documents \
    && chown -R 10001:0 /app/storage \
    && chmod -R ug+rwX /app/storage
EXPOSE 8080
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
