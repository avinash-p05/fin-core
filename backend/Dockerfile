# Build stage: Maven wrapper + JDK 21
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# Runtime stage: JRE only, non-root user
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S fincore && adduser -S fincore -G fincore
WORKDIR /app
COPY --from=build /workspace/target/fincore-backend-*.jar app.jar
USER fincore
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
