# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# pom.xml and dependencies first: a code change doesn't re-download all of Maven
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
# Tests run in CI; this build container has no Docker for Testcontainers anyway
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---- run ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
# Least-changing layers first, so a code change only rebuilds the small application layer
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
