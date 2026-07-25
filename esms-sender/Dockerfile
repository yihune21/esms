# ===================================================================
# eSMS-Sender — Multi-stage Docker build
# ===================================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY esms-common/pom.xml esms-common/pom.xml
COPY esms-core/pom.xml esms-core/pom.xml
COPY esms-sender/pom.xml esms-sender/pom.xml
RUN mvn dependency:go-offline -pl esms-common,esms-sender -am -B
COPY esms-common/src esms-common/src
COPY esms-sender/src esms-sender/src
RUN mvn package -pl esms-common,esms-sender -am -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S esms && adduser -S esms -G esms
COPY --from=build /app/esms-sender/target/*.jar app.jar
RUN chown -R esms:esms /app
USER esms
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
