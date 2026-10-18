# syntax=docker/dockerfile:1
# Dockerfile UNIQUE pour tous les services : docker build --build-arg SERVICE=collector-service .
# Etape 1 : build Maven du service et de ses modules internes (-am), sans Maven local necessaire.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
ARG SERVICE
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${SERVICE} -am package -Dmaven.test.skip=true

# Etape 2 : image d'execution minimale, utilisateur non root
FROM eclipse-temurin:17-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 10001 app
WORKDIR /app
ARG SERVICE
COPY --from=build /workspace/${SERVICE}/target/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
