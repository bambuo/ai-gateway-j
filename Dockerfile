FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src/ src/
RUN mvn package -DskipTests -q

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/gateway.jar gateway.jar
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "gateway.jar"]
