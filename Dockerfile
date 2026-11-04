# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# pom.xml and dependencies first: a code change doesn't re-download all of Maven
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
# Tests run in CI; this build container has no Docker for Testcontainers anyway.
# Extracted as app.jar + lib/ (no --launcher): a plain "java -jar" classpath is what the AOT cache needs.
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted \
 && mv extracted/application/*.jar extracted/application/app.jar

# ---- run ----
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
# Least-changing layers first, so a code change only rebuilds the small application layer
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./
# Small-instance tuning (512 MB, 0.1 CPU), set here so the training run below uses the same JVM options as production:
# an AOT cache is only used by a JVM started like the one that created it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
# AOT cache training run (Java 25, JEP 483/514/515): start until the Spring context is ready, then exit and save
# the loaded and linked classes plus method profiles to app.aot.
# MaxRAM=512m: size the heap as on the 512 MB instance, not from the build machine's RAM. A cache trained with a
# multi-GB heap holds objects a ~300 MB heap can't load, and then the JVM refuses to start at all.
# There is no database at build time, so Flyway and schema validation are off and Hibernate must not connect.
RUN java -XX:AOTCacheOutput=app.aot -XX:MaxRAM=512m -Dspring.context.exit=onRefresh \
      -Dspring.datasource.url=jdbc:postgresql://localhost:5432/aot-training \
      -Dspring.flyway.enabled=false \
      -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect \
      -jar app.jar
USER app
EXPOSE 8080
# -Xms32m: with Serial GC the cached objects are copied into the heap at startup and don't fit the default 8 MB
# initial heap (the JVM would refuse to start). On the command line, so a JAVA_TOOL_OPTIONS set on Render can't drop it.
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-Xms32m", "-jar", "app.jar"]
