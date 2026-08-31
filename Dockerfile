FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre@sha256:371da296b8cb74c7e53fbe7083d5374befc0011b493231d97d45fa789915e434
WORKDIR /app
COPY --from=build /workspace/target/agents-workbook-0.1.0-SNAPSHOT.jar /app/proxy.jar
# A fresh named volume inherits this directory's ownership and mode, which is the only way the
# unprivileged runtime user can write the optional trace archive into it.
RUN mkdir -p /var/lib/workbook \
    && chown 65532:65532 /var/lib/workbook \
    && chmod 700 /var/lib/workbook
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/app/proxy.jar"]
