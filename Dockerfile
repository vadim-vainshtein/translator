# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

COPY src/main/ src/main/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN groupadd --gid 10001 translator \
    && useradd --uid 10001 --gid translator --no-create-home translator

COPY --from=build --chown=translator:translator \
    /workspace/target/translator-*.jar /app/translator.jar

USER translator
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD test "$(curl --silent --output /dev/null --write-out '%{http_code}' \
        --max-time 3 http://127.0.0.1:8080/v1/models)" = 401

ENTRYPOINT ["java", "-jar", "/app/translator.jar"]
