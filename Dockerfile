# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# pom.xml and dependencies first: a code change doesn't re-download all of Maven
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
# Tests run in CI; this build container has no Docker for Testcontainers anyway.
# Extracted as app.jar + lib/ (no --launcher): a plain "java -jar" classpath is what CDS needs.
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted \
 && mv extracted/application/*.jar extracted/application/app.jar

# ---- run ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
# Least-changing layers first, so a code change only rebuilds the small application layer
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./
# CDS training run: start until the Spring context is ready, then exit and save every loaded class to app.jsa.
# There is no database at build time, so Flyway and schema validation are off and Hibernate must not connect.
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
      -Dspring.datasource.url=jdbc:postgresql://localhost:5432/cds-training \
      -Dspring.flyway.enabled=false \
      -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect \
      -jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "app.jar"]
